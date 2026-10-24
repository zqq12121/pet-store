package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.mapper.ResourceMapper;
import com.warmpaw.repository.RelationalRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 保持现有业务契约，实际读写独立领域表；快照与可变商品数据隔离。
 *
 * <p>这是 {@code repository.BusinessRepository} 的<b>精简孪生体</b>：方法体逐行相同
 * （get/byKey/list/create/save 的默认值填充逻辑一字不差），但<b>不含</b>
 * {@code lock/occupy/occupation/release/replay/remember/ownedOrder} 这组交易侧能力，
 * 并且把 {@link ResourceMapper} 暴露成 {@code public final} 字段（而不是私有）。
 *
 * <p><b>现状</b>：全仓已没有任何类 import 它，实际生效的是 {@code BusinessRepository}。
 * 保留它是因为两边的 Map 契约必须一致——如果将来有代码误注入了这个类，
 * 行为（除缺少加锁/幂等外）与 BusinessRepository 完全相同，不会出现"字段默认值不一样"的诡异偏差。
 * 新增业务请直接用 {@code BusinessRepository}。
 *
 * <p>两个版本的 {@code create} 都体现同一条约定：<b>缺少的元字段由仓储层补齐</b>
 * （{@code id/status=ready/version=1/createdAt/updatedAt}），调用方只给业务字段。
 * {@code save} 则负责 {@code updatedAt} 与 {@code version+1}——版本号递增发生在这里，
 * 而不是数据库触发器里，所以<b>绕过 save 直接 update 的代码不会递增版本</b>。
 */
@Component
public class Store {
  /** 公开给同包/历史调用方直接写审计等边缘 SQL；主流程应走本类方法而不是直接用它。 */
  public final ResourceMapper mapper;

  private final RelationalRepository repository;

  public Store(ResourceMapper mapper, RelationalRepository repository) {
    this.mapper = mapper;
    this.repository = repository;
  }

  /** 查不到返回 null。需要"不存在即 404"的调用方请用 {@link #get}。 */
  public Map<String, Object> find(String kind, String id) {
    return repository.find(kind, id);
  }

  /**
   * 查不到直接 404 {@code RESOURCE_NOT_FOUND}。
   *
   * <p>错误文案刻意统一为"资源不存在或不可访问"：把"不存在"和"无权限"合成一句话，
   * 调用方就无法通过 404/403 的差别去探测某个 id 是否存在。
   */
  public Map<String, Object> get(String kind, String id) {
    Map<String, Object> m = find(kind, id);
    require(m != null, 404, "RESOURCE_NOT_FOUND", "资源不存在或不可访问");
    return m;
  }

  public Map<String, Object> byKey(String kind, String key) {
    return repository.byKey(kind, key);
  }

  public List<Map<String, Object>> list(String kind) {
    return repository.list(kind);
  }

  /**
   * 新建实体：补默认元字段后插入，返回<b>同一个</b> Map（不是新对象）。
   *
   * <p>四个 {@code putIfAbsent} 的顺序很重要：{@code updatedAt} 取的是刚填好的 {@code createdAt}，
   * 保证"新建即修改时间等于创建时间"，不会因为调用方预先塞了 {@code createdAt} 而对不上。
   *
   * <p>{@code owner} 非空时<b>强制覆盖</b> {@code ownerId}（不是 putIfAbsent）：
   * 归属只能由服务端从认证主体推出，不能由请求体决定。
   *
   * <p>{@code key} 写进 {@code business_key}；为 null 时该行为空，唯一索引允许多个 NULL。
   */
  public Map<String, Object> create(String kind, String owner, String key, Map<String, Object> m) {
    m.putIfAbsent("id", id(kind));
    m.putIfAbsent("status", "ready");
    m.putIfAbsent("version", 1);
    m.putIfAbsent("createdAt", Instant.now().toString());
    m.putIfAbsent("updatedAt", m.get("createdAt"));
    if (owner != null) m.put("ownerId", owner);
    repository.insert(kind, key, m);
    return m;
  }

  /**
   * 保存实体：刷新 {@code updatedAt} 并让 {@code version} 自增，然后整行写回。
   *
   * <p>版本号自增是<b>写侧</b>做的事情，读侧不参与；配合 {@link #version} 的比对，
   * 构成"客户端带 version → 服务端比对 → save 递增"的乐观锁闭环。
   *
   * <p>{@code version} 不是 Number 时跳过自增（不报错）：兼容早期没有版本字段的数据，
   * 这类记录将永远不参与乐观锁校验。
   *
   * <p>没有事务注解——它依赖调用方（通常已有 {@code @Transactional}）或 Spring 的自动提交。
   * 单独调用时子表替换不在同一事务内，属于不安全的用法。
   */
  public void save(Map<String, Object> m) {
    m.put("updatedAt", Instant.now().toString());
    if (m.get("version") instanceof Number) m.put("version", number(m, "version") + 1);
    repository.update(m);
  }

  /** 写一条审计日志；{@code actor}/{@code action} 必须是服务端已知值，不接受请求体直传。 */
  public void audit(String actor, String action, String resource) {
    mapper.audit(id("audit"), actor, action, resource, Instant.now().toString());
  }

  /**
   * 乐观锁校验：实体当前版本必须等于客户端带回来的版本，否则 409。
   *
   * <p>用 {@code require} 而不是返回 boolean：调用方忘了判断返回值就等于没校验，
   * 而抛异常无法被忽略。错误码 {@code VERSION_CONFLICT} 让前端能提示"数据已更新，请刷新"。
   */
  public static void version(Map<String, Object> entity, long expected) {
    require(number(entity, "version") == expected, 409, "VERSION_CONFLICT", "数据已更新，请重新加载");
  }

  /**
   * 状态机守卫：当前状态必须属于允许集合，否则 409 {@code ORDER_STATE_CONFLICT}。
   *
   * <p>用 409（冲突）而不是 400（参数错）：请求本身是合法的，只是"此刻不能这么操作"，
   * 客户端刷新后可能就合法了（比如支付掉线后又恢复）。
   *
   * <p>变参设计让调用点能一行列出所有合法前态，比如"只有待支付/已支付可取消"。
   */
  public static void state(Map<String, Object> entity, String... states) {
    require(
        Arrays.asList(states).contains(text(entity, "status")),
        409,
        "ORDER_STATE_CONFLICT",
        "当前状态不允许此操作");
  }
}
