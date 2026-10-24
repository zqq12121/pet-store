package com.warmpaw.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

/**
 * 所有动态值均参数绑定；单店写事务按固定行加锁，避免跨订单/退款的锁序死锁。
 *
 * <p><b>为什么这些操作不走通用模型映射</b>（{@code RelationalRepository}）：
 *
 * <ul>
 *   <li>{@link #lock} 需要 {@code SELECT ... FOR UPDATE}——模型层只做普通查询，没有加锁钩子；
 *   <li>{@link #occupy} 靠<b>主键冲突</b>抢占宠物，语义是"插入失败即被占"，模型层的 insert
 *       会把冲突包成同样的异常但拿不到"是哪个约束"的语义，也无法表达"占用"这个状态；
 *   <li>{@link #release} 必须带 {@code order_id} 条件，只允许占用者自己释放；
 *   <li>{@link #replay}/{@link #remember} 是幂等表，键是 {@code scope_hash} 而不是业务 id，
 *       且读写都要求"原样返回/原样写入整行"，不经过字段白名单（模型层会丢弃未声明字段）；
 *   <li>{@link #audit} 是只增表，没有实体 id 之外的业务语义，也没必要建模。
 * </ul>
 *
 * <p>参数用 {@code #{name}} 具名绑定，依赖 {@code -parameters} 编译（Spring Boot 父 POM 默认开启）；
 * 关闭后启动时会报参数名解析失败，而不是静默用错值。
 *
 * <p><b>加锁顺序约定</b>：任何写事务的第一件事都是 {@link #lock}，之后才去碰订单/宠物/退款。
 * 因为只有一把锁，锁序不可能出现环，从结构上消除了死锁（代价是写事务完全串行）。
 */
@Mapper
public interface ResourceMapper {
  /**
   * 取单店写事务的全局排他锁，直到当前事务提交/回滚才释放。
   *
   * <p>锁的是 {@code business_lock} 表里 id=1 这一固定行——用一个"哑表"当互斥量，
   * 不需要额外的分布式锁组件，也不依赖数据库的 advisory lock 方言。
   *
   * <p><b>必须在事务内调用</b>：自动提交模式下 {@code FOR UPDATE} 的行锁在语句结束即释放，
   * 等于没加锁。调用方（{@code BusinessRepository.lock}）都在 {@code @Transactional} 里。
   *
   * <p>返回值只是为了让 MyBatis 走查询而不是更新，调用方从不关心内容。
   */
  @Select("SELECT id FROM business_lock WHERE id=1 FOR UPDATE")
  int lock();

  /**
   * 占用宠物：在 {@code pet_occupancy} 里插一条 (pet_id, order_id)。
   *
   * <p>{@code pet_id} 是主键，所以同一只宠物第二次占用会抛
   * {@code DuplicateKeyException} → HTTP 409。"检查是否已占用"和"占用"是同一个原子操作，
   * 不存在先查后插的竞态窗口——这是订单不漏卖的根本保证。
   */
  @Insert("INSERT INTO pet_occupancy(pet_id,order_id) VALUES(#{pet},#{order})")
  void occupy(String pet, String order);

  /** 查当前占用者；返回 null 表示可售。用于展示层判断，不构成并发保证（见上）。 */
  @Select("SELECT order_id FROM pet_occupancy WHERE pet_id=#{pet}")
  String occupation(String pet);

  /**
   * 释放占用，返回受影响行数。
   *
   * <p>条件里带 {@code order_id}：只有"占用它的那张订单"能释放。少了这个条件，
   * 任意订单都能把别人的宠物放回货架，从而造成超卖。
   *
   * <p>返回 0 表示本来就没占用（重复释放/已取消），调用方据此决定是否报错。
   */
  @Delete("DELETE FROM pet_occupancy WHERE pet_id=#{pet} AND order_id=#{order}")
  int release(String pet, String order);

  /**
   * 按幂等作用域读已保存的响应快照。
   *
   * <p>{@code SELECT *} 返回的是<b>数据库行</b>，键是列名（{@code body_hash}、
   * {@code response_body}、{@code http_status}）——所以调用方读的是下划线风格，
   * 不是实体的驼峰风格。这一点容易被 {@code Json.text(m, key)} 的通用外观掩盖。
   */
  @Select("SELECT * FROM idempotency WHERE scope_hash=#{scope}")
  Map<String, Object> replay(String scope);

  /**
   * 记录一次成功响应的快照，供同键重放。
   *
   * <p>纯 INSERT，不做 upsert：{@code scope_hash} 是主键，同一键并发提交时第二个会撞主键。
   * 这正是想要的——并发重复请求应当有一个失败（409），而不是两笔业务都执行。
   *
   * <p>{@code body} 已由调用方序列化成 JSON 文本；{@code status} 是该次业务的响应状态码，
   * 重放时必须原样返回，否则客户端会看到"第一次 202、第二次 200"。
   */
  @Insert(
      "INSERT INTO"
          + " idempotency(scope_hash,body_hash,resource_id,response_body,http_status,created_at)"
          + " VALUES(#{scope},#{hash},#{resource},#{body},#{status},#{now})")
  void remember(String scope, String hash, String resource, String body, int status, String now);

  /**
   * 追加一条审计日志。
   *
   * <p>只增不改不删。{@code action} 是固定的动作名（如 {@code password_changed}），
   * 由服务端写死，不接受客户端传入；{@code actor} 取自已认证主体，保证"谁做的"不可伪造。
   *
   * <p>写审计与业务写在同一事务里，业务回滚则审计一并回滚——不会留下"操作没成功但记了一笔"。
   */
  @Insert(
      "INSERT INTO audit_log(id,actor_id,action,resource_id,occurred_at)"
          + " VALUES(#{id},#{actor},#{action},#{resource},#{now})")
  void audit(String id, String actor, String action, String resource, String now);
}
