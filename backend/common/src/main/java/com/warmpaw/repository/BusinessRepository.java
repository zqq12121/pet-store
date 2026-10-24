package com.warmpaw.repository;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.mapper.ResourceMapper;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 业务仓储门面：业务代码访问数据的唯一入口。
 *
 * <p><b>为什么要有这一层</b>：项目里并存两套数据访问方式——
 *
 * <ul>
 *   <li>{@link ResourceMapper}：MyBatis 注解写显式 SQL，承担<b>带锁、原子占位、幂等回放</b>
 *       这类必须精确控制语义的操作；
 *   <li>{@link RelationalRepository}：JdbcTemplate + 模型驱动的通用映射，
 *       承担普通增删改查。
 * </ul>
 *
 * <p>业务层不应该关心"这次该走哪一边"。本类把两者合并成一个统一的门面，
 * 并在这里做三件所有业务都需要的事：<b>实体不存在时统一 404</b>、
 * <b>新增时补齐通用字段</b>、<b>修改时自增版本号</b>。
 *
 * <p><b>快照与可变数据隔离</b>：订单会冻结商品、价格、门店、协议的副本，
 * 之后读取一律用快照。本类不感知这一点——快照就是普通字段，
 * 由 {@code OrderService} 在创建时写入。这样历史订单不会被后来的改价影响。
 *
 * <p><b>事务与锁</b>：本类不开启事务。写操作要求调用方已持有全局锁
 * （{@link #lock()}），否则 {@code occupy}/{@code release} 这类条件更新会失效。
 */
@Repository
public class BusinessRepository {
  /**
   * 状态变更日志。与审计表配合使用：表负责持久化与事后追查，日志负责"问题现场直接可见"。
   * 用静态字段而不是每次调用 {@code LoggerFactory.getLogger}，避免高频写路径上的重复查找。
   */
  private static final Logger log = LoggerFactory.getLogger(BusinessRepository.class);

  private final ResourceMapper mapper;

  private final RelationalRepository repository;

  public BusinessRepository(ResourceMapper mapper, RelationalRepository repository) {
    this.mapper = mapper;
    this.repository = repository;
  }

  /**
   * 获取单店全局业务锁。
   *
   * <p>这是整个系统并发控制的基础：所有写事务的第一步都是它。
   * 用一把锁而不是分表锁，是为了避免订单与退款各自持锁、互相等待造成死锁
   * ——单店场景下写并发很低，全局锁的吞吐损失可以忽略，正确性收益很大。
   *
   * <p><b>必须在事务内调用</b>，锁才会在事务结束时释放。
   *
   * <p>单店写事务统一加锁，避免订单与退款各自采用不同锁序而互相等待。
   */
  public void lock() {
    mapper.lock();
  }

  /**
   * 占用宠物库存：把 {@code pet.active_order_id} 置为本订单。
   *
   * <p>走手写 SQL 而非通用更新，是因为它需要"只有当前未被占用才成功"的原子语义
   * ——查询再更新会留下竞态窗口，两笔订单可能都认为自己占到了。
   */
  public void occupy(String petId, String orderId) {
    mapper.occupy(petId, orderId);
  }

  /** 查询这只宠物当前被哪个订单占用，null 表示空闲。用于交付前的一致性核对。 */
  public String occupation(String petId) {
    return mapper.occupation(petId);
  }

  /**
   * 释放库存，返回受影响行数。
   *
   * <p>返回 int 而不是 void 是刻意的：调用方用 {@code > 0} 判断"锁确实属于本单"。
   * 若并发下已被别的订单接管，这里返回 0，调用方就不会误改宠物状态。
   */
  public int release(String petId, String orderId) {
    return mapper.release(petId, orderId);
  }

  /** 读取某作用域的幂等记录（首次请求的响应快照），用于重复请求时回放。 */
  public Map<String, Object> replay(String scope) {
    return mapper.replay(scope);
  }

  /** 持久化幂等记录：作用域 + 请求体哈希 + 资源类型/id + 响应体 + 状态码。 */
  public void remember(
      String scope, String hash, String resource, String body, int status, String now) {
    mapper.remember(scope, hash, resource, body, status, now);
  }

  /** 按 id 查找实体，不存在返回 null（不抛异常）。 */
  public Map<String, Object> find(String kind, String id) {
    return repository.find(kind, id);
  }

  /**
   * 按 id 取实体，不存在直接 404。
   *
   * <p>绝大多数业务都用这个方法：找不到资源是常态化的错误路径，
   * 让它自动抛 {@code RESOURCE_NOT_FOUND} 可以省掉大量 if 判断。
   *
   * <p>错误信息统一为"资源不存在或不可访问"，不区分"不存在"与"无权访问"——
   * 后者会让攻击者通过 404/403 差异探测哪些 id 真实存在。
   */
  public Map<String, Object> get(String kind, String id) {
    Map<String, Object> m = find(kind, id);
    require(m != null, 404, "RESOURCE_NOT_FOUND", "资源不存在或不可访问");
    return m;
  }

  /** 按业务键查找（如手机号、用户名、商户订单号）。业务键在 insert 时写入且不可变。 */
  public Map<String, Object> byKey(String kind, String key) {
    return repository.byKey(kind, key);
  }

  /** 按用户名查买家。单独抽出来是因为它要处理大小写归一化。 */
  public Map<String, Object> userByUsername(String username) {
    return repository.userByUsername(username);
  }

  /** 撤销某账号在指定角色域下的全部会话。改密、停用员工后必须调用。 */
  public void revokeSessions(String owner, String role) {
    repository.revokeSessions(owner, role);
  }

  /** 取某类实体的全量列表。单店数据量小，业务层普遍在内存里过滤。 */
  public List<Map<String, Object>> list(String kind) {
    return repository.list(kind);
  }

  /**
   * 条件 + 关键词 + 分页的列表查询，过滤与分页下推到 SQL。
   *
   * <p>门面只做转发：安全边界（字段白名单、参数绑定）在 {@link RelationalRepository} 里，
   * 这里不重复实现，也不允许调用方绕过。
   */
  public List<Map<String, Object>> list(
      String kind,
      List<Filter> filters,
      String keyword,
      List<String> keywordFields,
      int offset,
      int limit) {
    return repository.list(kind, filters, keyword, keywordFields, offset, limit);
  }

  /** 同条件计数，供分页的 {@code total} 使用。 */
  public long count(
      String kind, List<Filter> filters, String keyword, List<String> keywordFields) {
    return repository.count(kind, filters, keyword, keywordFields);
  }

  /**
   * 创建实体，并补齐五个通用字段。
   *
   * <p>用 {@code putIfAbsent} 而不是直接覆盖，是为了允许调用方预置值
   * （比如订单要指定创建时间与初始状态）。
   *
   * <p>通用字段：{@code id}（系统生成）、{@code status}（默认 ready）、
   * {@code version}（从 1 开始）、{@code createdAt}、{@code updatedAt}。
   *
   * <p>{@code owner} 非空时写入 {@code ownerId}，这是归属校验的基础；
   * 系统级实体（如门店）传 null。
   *
   * <p>{@code key} 是业务键（可为 null），只在 insert 时写入，之后不可变。
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
   * 保存修改：刷新 {@code updatedAt} 并自增 {@code version}。
   *
   * <p><b>注意乐观锁的实际形态</b>：版本号自增发生在应用层，UPDATE 语句本身不带
   * {@code WHERE version = ?}。真正的并发保护来自全局锁（所有写都在锁内），
   * 版本号主要用于"客户端拿着旧数据提交"这类跨请求场景，由
   * {@link #version} 显式比对。
   */
  public void save(Map<String, Object> m) {
    m.put("updatedAt", Instant.now().toString());
    if (m.get("version") instanceof Number) m.put("version", number(m, "version") + 1);
    repository.update(m);
  }

  /**
   * 写审计日志。所有敏感写操作（改价、上下架、退款审批、查看他人订单）都应调用。
   *
   * <p><b>这里同时打一行 INFO 日志，是全项目状态变更日志的唯一出口。</b>
   * 不把日志散落到各个 service 方法的理由：{@code audit} 的调用点已经覆盖了所有需要留痕的写操作
   * （预约创建/取消/确认、订单交付、售后审核、上下架、员工变更、社区处置等 20 余处），
   * 在汇聚点打一次等于全部覆盖，既不会漏也不会重复。
   *
   * <p>日志只打 {@code action} / {@code actor} / {@code resource} 三个标识，
   * <b>不打用户名、手机号、金额</b>——日志的留存周期和访问范围都比数据库宽，
   * 敏感字段留在库里更可控，需要时按 id 回查即可。
   *
   * <p>放在 {@code mapper.audit} 之后而不是之前：写库失败会抛异常，此时不该留下"操作已发生"的日志行。
   */
  public void audit(String actor, String action, String resource) {
    mapper.audit(id("audit"), actor, action, resource, Instant.now().toString());
    log.info("audit action={} actor={} resource={}", action, actor, resource);
  }

  /** 乐观锁断言：实体当前版本必须等于客户端提交的期望版本，否则 409。 */
  public static void version(Map<String, Object> entity, long expected) {
    require(number(entity, "version") == expected, 409, "VERSION_CONFLICT", "数据已更新，请重新加载");
  }

  /**
   * 状态机断言：实体当前状态必须是 {@code states} 之一。
   *
   * <p>这是全项目状态机校验的统一入口。错误码固定为 {@code ORDER_STATE_CONFLICT}，
   * 即使是非订单实体（预约、售后、协议）也复用同一个码——前端只需要处理一种"状态已变"。
   */
  public static void state(Map<String, Object> entity, String... states) {
    require(
        Arrays.asList(states).contains(text(entity, "status")),
        409,
        "ORDER_STATE_CONFLICT",
        "当前状态不允许此操作");
  }

  /**
   * 订单归属校验，读操作专用。
   *
   * <p>放在仓储层而不是订单服务，是为了让<b>文件服务也能校验订单归属而不必依赖交易服务</b>
   * ——否则 admin-server 就要为了一个归属判断去跨服务调用。
   *
   * <p>店长可读全部，买家只能读自己的。越权一律 404（不区分"不存在"与"无权"）。
   *
   * <p>文件服务与订单服务共用这个方法，避免前者依赖后者。
   */
  public Map<String, Object> ownedOrder(String id, com.warmpaw.service.AuthService.Actor actor) {
    Map<String, Object> o = get("order", id);
    require(
        actor != null && (actor.role().equals("admin") || actor.id().equals(text(o, "ownerId"))),
        404,
        "RESOURCE_NOT_FOUND",
        "订单不存在或不可访问");
    return o;
  }

}
