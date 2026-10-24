package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预约短信发件箱（outbox）。
 *
 * <p><b>为什么需要 outbox</b>：订单状态和"要发一条短信"必须原子地一起生效。
 * 如果直接在业务事务里调短信接口，会出现两种坏情况：短信发了但事务回滚（用户收到一条不存在的预约），
 * 或者事务提交了但短信调用失败（用户永远收不到通知）。outbox 把"发送"变成一条待办记录，
 * 与业务数据同事务写入；真正的发送由 {@link AppointmentSmsWorker} 在提交后异步完成。
 *
 * <p><b>并发认领</b>：多条 order-server 实例同时扫描时，靠一条带条件的 UPDATE 抢占
 * （{@code locked_until} 租约 + {@code attempts} 乐观计数），只有 {@code changed == 1} 的那一方能发。
 * 这是数据库层面的原子 CAS，不需要分布式锁。
 *
 * <p><b>失败策略</b>：指数退避，最多 8 次；最终失败保留在表里（{@code failed}）供后台排查，
 * 不静默丢弃——短信没发出去是业务事故，必须有痕迹。
 */
@Repository
public class AppointmentSmsOutbox {
  private static final int MAX_ATTEMPTS = 8;
  private final JdbcTemplate jdbc;

  public AppointmentSmsOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Worker 使用的不可变通知快照。
   *
   * <p>刻意把 {@code phone} 和 {@code parameters} 在这里固化，而不是发送时回查订单：
   * 订单联系人可能已被修改，短信内容必须反映"事件发生当时"的信息。
   *
   * <p>{@code attempts} 用来做乐观锁——回写结果时带上它，避免旧租约覆盖新租约。
   */
  public record Notice(
      String key,
      String orderId,
      String event,
      String phone,
      Map<String, Object> parameters,
      int attempts) {}

  /**
   * 登记一条待发短信。
   *
   * <p>{@code Propagation.MANDATORY} 是刻意选的：<b>必须已存在事务</b>，否则直接抛异常。
   * 这样能防止有人在不带事务的地方调用，导致 outbox 记录与业务状态脱节。
   *
   * <p>幂等键是 {@code orderId-event}：同一订单的同一事件只会有一条。
   * HTTP 层的幂等回放（同一个 Idempotency-Key 重放）因此不会产生第二条短信。
   *
   * <p>先 SELECT COUNT 再 INSERT 是为了给出"是否新插入"的返回值；并发下仍可能两个线程同时通过
   * COUNT 检查，此时由数据库唯一键兜底，捕获 {@link DuplicateKeyException} 后返回 false。
   *
   * @return true 表示确实新增了一条待发任务；false 表示已存在（无需重复调度）
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean enqueue(
      String orderId,
      String event,
      String phone,
      Map<String, Object> parameters,
      Instant now) {
    String key = orderId + "-" + event;
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM appointment_sms_outbox WHERE event_key=?", Integer.class, key);
    if (count != null && count > 0) return false;
    try {
      jdbc.update(
          "INSERT INTO appointment_sms_outbox"
              + "(event_key,order_id,event,phone,parameters,status,attempts,next_attempt_at,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,'pending',0,?,?,?)",
          key,
          orderId,
          event,
          phone,
          write(parameters),
          now.toString(),
          now.toString(),
          now.toString());
      return true;
    } catch (DuplicateKeyException ignored) {
      // 多实例极短竞态由数据库唯一键收口，调用方无需再次登记提交回调。
      return false;
    }
  }

  /**
   * 原子认领一条任务，租约 2 分钟。
   *
   * <p>认领前先跑一次"租约过期且已达上限"的清理：把 {@code sending} 且 {@code attempts >= 8} 的记录
   * 直接标为 {@code failed}。这条 SQL 解决的是"进程在发送过程中反复崩溃"的场景——
   * 否则一条记录可以永远停在 {@code sending}，每轮都被重新认领，形成无限重发。
   *
   * <p>认领 UPDATE 的 WHERE 覆盖四种状态（pending/retry/sending）：
   * {@code sending} 只有在租约过期后才会被再次选中，这就是"卡住的任务自动回收"。
   *
   * <p><b>注意</b>：本方法用 {@code REQUIRES_NEW} 独立事务。认领必须立刻提交，
   * 否则租约锁对其它实例不可见，等于没加锁。
   *
   * @return 认领成功返回快照（{@code attempts} 已包含本次），被他人抢占或达上限返回 null
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Notice claim(String key, Instant now) {
    Instant lease = now.plus(Duration.ofMinutes(2));
    // 第八次发送中断也必须终止；不能因进程一直崩溃而无限重发。
    jdbc.update(
        "UPDATE appointment_sms_outbox SET status='failed',locked_until=NULL,"
            + "last_error='LEASE_EXPIRED',updated_at=? WHERE event_key=? "
            + "AND status='sending' AND attempts>=? AND locked_until<=?",
        now.toString(), key, MAX_ATTEMPTS, now.toString());
    int changed =
        jdbc.update(
            "UPDATE appointment_sms_outbox SET status='sending',attempts=attempts+1,"
                + "locked_until=?,updated_at=? WHERE event_key=? "
                + "AND status IN ('pending','retry','sending') AND attempts<? AND next_attempt_at<=? "
                + "AND (locked_until IS NULL OR locked_until<=?)",
            lease.toString(),
            now.toString(),
            key,
            MAX_ATTEMPTS,
            now.toString(),
            now.toString());
    if (changed != 1) return null;
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT event_key,order_id,event,phone,parameters,attempts "
                + "FROM appointment_sms_outbox WHERE event_key=?",
            key);
    return new Notice(
        text(row, "event_key"),
        text(row, "order_id"),
        text(row, "event"),
        text(row, "phone"),
        read(text(row, "parameters")),
        ((Number) row.get("attempts")).intValue());
  }

  /**
   * 取出当前可发送的任务键，单次最多 20 条。
   *
   * <p>包含 {@code sending} 状态是刻意的：租约过期的 {@code sending} 也需要被重新拾起。
   * 限量 20 是为了让一轮扫描有明确上界，避免积压时一次性打爆短信平台。
   */
  public List<String> due(Instant now) {
    return jdbc.queryForList(
        "SELECT event_key FROM appointment_sms_outbox "
            + "WHERE status IN ('pending','retry','sending') AND next_attempt_at<=? "
            + "AND (locked_until IS NULL OR locked_until<=?) ORDER BY created_at LIMIT 20",
        String.class,
        now.toString(),
        now.toString());
  }

  /** 待发数量（含重试中），用于后台监控；只给数字，不暴露手机号与模板参数。 */
  public long pendingCount() {
    Long value =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM appointment_sms_outbox WHERE status IN ('pending','retry','sending')",
            Long.class);
    return value == null ? 0 : value;
  }

  /** 最终失败数量，用于后台告警与人工补发决策。 */
  public long failedCount() {
    Long value =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM appointment_sms_outbox WHERE status='failed'", Long.class);
    return value == null ? 0 : value;
  }

  /**
   * 标记发送成功。
   *
   * <p>WHERE 里同时带 {@code status='sending'} 与 {@code attempts=?}：
   * 如果这条记录已被别的实例重新认领（attempts 变了），本次回写不会生效，
   * 避免"旧任务把新任务标记成已发"。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void sent(String key, int attempts, Instant now) {
    jdbc.update(
        "UPDATE appointment_sms_outbox SET status='sent',sent_at=?,locked_until=NULL,"
            + "last_error=NULL,updated_at=? WHERE event_key=? AND status='sending' AND attempts=?",
        now.toString(),
        now.toString(),
        key,
        attempts);
  }

  /**
   * 记录一次失败并安排重试。
   *
   * <p>退避算法：{@code min(120, 2^(attempts-1))} 分钟，即 1、2、4、8、16、32、64、120 分钟，
   * 上限 2 小时。第 8 次（{@code attempts >= MAX_ATTEMPTS}）不再安排重试，直接置 {@code failed}。
   *
   * <p>指数退避在这里是必要的：短信平台故障通常持续几十秒到几分钟，
   * 固定间隔重试会在故障期间堆积请求，反而延长恢复时间。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retry(String key, int attempts, Instant now, String errorCode) {
    boolean exhausted = attempts >= MAX_ATTEMPTS;
    long delayMinutes = Math.min(120, 1L << Math.min(7, Math.max(0, attempts - 1)));
    jdbc.update(
        "UPDATE appointment_sms_outbox SET status=?,next_attempt_at=?,locked_until=NULL,"
            + "last_error=?,updated_at=? WHERE event_key=? AND status='sending' AND attempts=?",
        exhausted ? "failed" : "retry",
        now.plus(Duration.ofMinutes(delayMinutes)).toString(),
        errorCode,
        now.toString(),
        key,
        attempts);
  }
}
