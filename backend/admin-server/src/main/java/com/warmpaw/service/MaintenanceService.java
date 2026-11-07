package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.repository.BusinessRepository;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每日只清理未被业务引用的临时文件；历史订单、退回证据和快照优先保留。
 *
 * <p><b>清理什么</b>：{@code file} 表里状态为 {@code ready}、创建超过 24 小时、没有被任何业务实体
 * 引用、且自身不带 {@code orderId} 的文件。删除是<b>不可逆</b>的（OSS 与本地缓存一起删），
 * 所以判据刻意偏保守：只要扫描时"看起来被引用"，就留下。
 *
 * <p><b>为什么不是上传即清</b>：上传与业务落库不是原子的——用户可能传了图、关掉页面没提交。
 * 这类孤儿只有过一段时间才能确定，还必须避开"正在填表"的窗口（24 小时）；此外清理要拿全局写锁
 * 并逐条改状态，塞进请求链路会拖慢上传。
 *
 * <p><b>为什么首次执行要等 24 小时</b>：{@code initialDelay} 与 {@code fixedDelay} 相同，
 * 也就是说<b>启动时并不跑</b>，第一次要等一天。刚启动时业务数据可能还没就绪，
 * 立刻扫容易把"马上要被引用"的文件判成孤儿。
 *
 * <p><b>引用扫描范围</b>：{@code pet/order/after_sale/shop/confirmation/user/community_content}。
 * 社区帖子与评价的配图在 2026-11-07 前不在扫描范围内，发布一天后会被误清；已补上。
 * 新增业务若要往实体里存文件 id，必须同步扩展这里的 {@code kinds}，否则对应文件会在一天后被当作孤儿清掉。
 *
 * <p>除每日调度外，店长可在后台手动触发一次（{@link #cleanNow}），判据完全相同。
 */
@Service
public class MaintenanceService {
  /** 调度锁任务名；多实例必须一致。 */
  private static final String TASK_CLEAN = "admin:file-clean";

  /** 锁存活 1 小时：清理要扫全表，给足时间；仍远小于 24 小时的调度间隔。 */
  private static final Duration LOCK_TTL = Duration.ofHours(1);

  private final BusinessRepository store;
  private final Path root;
  private final OssStorage oss;
  /** 跨实例互斥：避免两个 admin 实例同时删同一批文件（第二个会因文件不存在而报错）。 */
  private final SchedulerLock schedulerLock;
  /**
   * 编程式事务：{@code @Transactional} 在本类自调用时不会生效（代理不拦截 this 调用），
   * 而加锁必须发生在事务之外——否则 Redis 抖动会连带回滚业务事务。
   */
  private final TransactionTemplate tx;

  public MaintenanceService(
      BusinessRepository store,
      OssStorage oss,
      @Value("${app.storage}") String storage,
      SchedulerLock schedulerLock,
      PlatformTransactionManager manager) {
    this.store = store;
    this.root = Path.of(storage).toAbsolutePath().normalize();
    this.oss = oss;
    this.schedulerLock = schedulerLock;
    this.tx = new TransactionTemplate(manager);
  }

  /**
   * 每日调度入口：先抢分布式锁，再在独立事务里执行清理。
   *
   * <p>锁放在事务之外，是为了让「Redis 抖动」和「业务事务回滚」互相独立——
   * 否则锁操作失败会连带把清理事务标记为回滚。
   *
   * <p>{@code initialDelay} 与 {@code fixedDelay} 相同，意味着<b>启动时并不跑</b>，
   * 第一次要等一天。刚启动时业务数据可能还没就绪，立刻扫容易把"马上要被引用"的文件判成孤儿。
   */
  @Scheduled(fixedDelay = 86400000, initialDelay = 86400000)
  public void cleanTemporaryFiles() {
    // 拿不到锁说明别的实例正在清理，本轮直接跳过（单实例时恒为 true）。
    if (!schedulerLock.tryAcquire(TASK_CLEAN, LOCK_TTL)) return;
    try {
      tx.executeWithoutResult(status -> cleanOnce());
    } finally {
      schedulerLock.release(TASK_CLEAN);
    }
  }

  /**
   * 手动触发一次清理（店长后台用），与每日任务共用同一把锁与同一套判据。
   *
   * @return 本次清理的文件数
   * @throws ApiException 409：另一轮清理正在进行
   */
  public int cleanNow() {
    if (!schedulerLock.tryAcquire(TASK_CLEAN, LOCK_TTL))
      throw new ApiException(409, "CLEANUP_IN_PROGRESS", "文件清理正在进行，请稍后再试");
    try {
      Integer cleaned = tx.execute(status -> cleanOnce());
      return cleaned == null ? 0 : cleaned;
    } finally {
      schedulerLock.release(TASK_CLEAN);
    }
  }

  /**
   * 执行一轮清理。四步：加锁 → 收集引用 → 逐条判断 → 删存储并改状态。
   *
   * <p>跳过条件（命中任一即保留）：状态不是 {@code ready}、创建时间不足 24 小时、被引用、
   * 或自身带 {@code orderId}。最后一条单列，是因为<b>订单附件永远保留</b>——
   * 诊断材料与退回证据是纠纷依据，不参与垃圾回收。
   *
   * <p>删除顺序是先远端、再本地、最后把记录改成 {@code deleted}：记录留着，
   * 事后能查到"这个文件曾经存在、什么时候被清理"。
   *
   * <p>路径先 {@code normalize} 再校验父目录，防止 id 里藏 {@code ..} 越出存储根。
   * 任一步抛异常都不改状态、交给明天重试——绝不能"删失败了还标成已删"。
   */
  private int cleanOnce() {
    int cleaned = 0;
    store.lock();
    Instant cutoff = Instant.now().minusSeconds(86400);
    Set<String> references = new HashSet<>();
    for (String kind :
        List.of("pet", "order", "after_sale", "shop", "confirmation", "user", "community_content"))
      for (Map<String, Object> entity : store.list(kind)) collect(entity, references);
    for (Map<String, Object> file : store.list("file")) {
      String id = text(file, "id");
      if (!"ready".equals(text(file, "status"))
          || Instant.parse(text(file, "createdAt")).isAfter(cutoff)
          || references.contains(id)
          || file.get("orderId") != null) continue;
      Path target = root.resolve(id).normalize();
      if (!target.getParent().equals(root)) continue;
      try {
        oss.delete(id);
        Files.deleteIfExists(target);
        file.put("status", "deleted");
        store.save(file);
        cleaned++;
      } catch (Exception ignored) {
        /* 文件系统失败保留元数据，下次任务重试，不误报清理成功。 */
      }
    }
    return cleaned;
  }

  /**
   * 递归收集实体里出现过的文件 id。
   *
   * <p>两种形态都认：裸的 {@code file_xxx}（存的是 id），以及 {@code /api/v1/media/file_xxx}
   * （存的是链接）。这是"宁可错留"的策略——只要某个字符串里出现 {@code file_} 前缀就当作引用，
   * 代价是正文里恰好写了文件名会阻止清理，收益是永远不误删还在用的附件。
   *
   * <p>注意第二种形态是从 {@code /api/v1/media/} 之后<b>整段截到字符串末尾</b>：
   * 带 {@code ?access=...} 的临时授权链接会被截成 {@code file_xxx?access=...}，
   * 与真实 id 对不上，等于没收集到。所以只有不带参数的 {@code publicUrl} 真正起作用；
   * 若日后业务实体里开始存签名链接，这里必须改成按 {@code ?} 截断。
   */
  private void collect(Object value, Set<String> result) {
    if (value instanceof String s) {
      if (s.startsWith("file_")) result.add(s);
      int start = s.indexOf("/api/v1/media/file_");
      if (start >= 0) result.add(s.substring(start + "/api/v1/media/".length()));
    } else if (value instanceof Map<?, ?> m) m.values().forEach(v -> collect(v, result));
    else if (value instanceof List<?> l) l.forEach(v -> collect(v, result));
  }
}
