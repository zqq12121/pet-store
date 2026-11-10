package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 业务公共边界：统一事务、权限和幂等，不包含接口路径路由。
 *
 * <p><b>它是全项目所有写请求的骨架</b>。一个请求进来后，顺序固定为：
 *
 * <ol>
 *   <li><b>鉴权</b>——{@link AccessLevel#check}，在<b>任何数据库操作之前</b>。未登录/角色不符
 *       在这里就被拒，不会占用锁、不会开启业务查询；
 *   <li><b>全局写锁</b>——{@code GET} 以外一律 {@code store.lock()}，即
 *       {@code SELECT ... FOR UPDATE} 锁住 {@code business_lock} 的单行。单店场景用一把锁
 *       把写事务串行化：订单、退款、交付、售后共用同一把锁，因此不可能出现锁序环（死锁），
 *       代价是写吞吐约等于 1 并发；
 *   <li><b>持锁后二次鉴权</b>——后台身份再跑一次 {@code requireActiveSession}。
 *       因为第 1 步到拿到锁之间可能等了几百毫秒，这期间员工可能被停用、会话可能被撤销
 *       （TOCTOU 防护）。买家侧不复检，因为买家没有"被停用"这种状态；
 *   <li><b>幂等</b>——{@code write && idempotent} 时才生效（见下方说明）；
 *   <li><b>业务方法</b>——{@code operation.get()}。它就是事务体，任何 {@code ApiException}
 *       都会让整个事务回滚；
 *   <li><b>审计</b>——<b>不在本类</b>，而是由业务方法自己在同一事务里调用
 *       {@code store.audit(...)}。这样审计与业务写同生共死，不会出现"操作失败但记了一笔"；
 *   <li><b>幂等结果落库</b>——业务成功后写 {@code idempotency} 行；
 *   <li><b>afterCommit 钩子</b>——<b>也不在本类</b>，由调用方 {@code ApiRequestExecutor}
 *       在本方法（事务代理）返回之后调用，所以那时事务已提交（见 {@link BusinessHooks}）。
 * </ol>
 *
 * <p><b>幂等的判据</b>：{@code idem = write && idempotent}。{@code GET} 永远不做幂等记录
 * （读请求本来就可以重复）；{@code idempotent=false} 的写接口（如"关闭全部会话"这类
 * 天然可重复的）也不记录。
 *
 * <p><b>作用域</b>：{@code hash(actorId + method + path + key)}。四个要素缺一不可——
 * 少了 actor，两个用户用同一个 UUID 就会互相串结果；少了 path，同一用户对
 * {@code /orders} 与 {@code /after-sales} 用同一个 key 也会串。key 用 UUID 格式强制，
 * 是让客户端"每条请求一个新键"，而不是用业务 id 当键（那会与"重试同一次操作"的语义混淆）。
 *
 * <p><b>事务边界</b>：整个 {@code execute} 是一个事务，包含全局锁的持有期。
 * 因此 business 方法里<b>不能</b>做外部网络调用（支付、短信），否则会把全局锁拖到超时——
 * 这类副作用统一放到 afterCommit。
 *
 * <p>不负责：路径路由、HTTP 编解码、响应包装、审计写入、领域规则。
 */
@Service
public class BusinessOperationExecutor {
  private final BusinessRepository store;
  private final BusinessHooks hooks;
  private final AuthService auth;
  public BusinessOperationExecutor(BusinessRepository store, BusinessHooks hooks, AuthService auth) {
    this.store = store;
    this.hooks = hooks;
    this.auth = auth;
  }

  /**
   * 一次业务调用一个事务：先鉴权，再加锁、重放、执行业务、保存幂等结果。
   *
   * <p><b>参数</b>：
   *
   * <ul>
   *   <li>{@code context} —— 方法/路径/幂等键/主体/IP，全部来自 HTTP 层；
   *   <li>{@code body} —— 原始请求体，只用于计算"请求指纹"，不参与业务（业务方法自己解析）；
   *   <li>{@code access} —— 该接口的访问级别，<b>显式声明</b>而不是从 URL 前缀推断；
   *   <li>{@code idempotent} —— 该接口是否需要幂等保护；
   *   <li>{@code operation} —— 真正的业务逻辑，返回 {@link OperationResult}。
   * </ul>
   *
   * <p><b>为什么用 Supplier 而不是先 execute 再传结果</b>：业务方法必须在锁内、
   * 事务内执行，把它作为回调传进来，本类才能保证"拿到锁之后才跑"。
   * 如果写成"调用方先跑业务、再交给执行器记录"，锁和事务就都失效了。
   *
   * <p><b>重放路径</b>：命中幂等记录时不执行业务，但会校验请求体指纹——同一个 key
   * 配不同 body 是客户端 bug（或键复用攻击），回 <b>409 IDEMPOTENCY_CONFLICT</b> 而不是
   * 悄悄返回旧结果。body 为空时 {@code canonical(null)} 得到 {@code "null"}，同样参与比较。
   *
   * <p>重放时的响应由 {@link BusinessHooks#replay} 现场重建（而不是直接回放存下来的 body），
   * 这样"支付已成功""订单已交付"这类状态推进能体现在第二次响应里。
   *
   * <p>返回的 {@code status} 是<b>业务状态码</b>（如取消订单 202），由 HTTP 层原样使用，
   * 所以重放必须返回与首次相同的状态码，否则客户端会看到状态跳变。
   *
   * @throws ApiException 鉴权失败（401/403）、幂等键格式错误（400）、
   *                      幂等键复用但内容不同（409）、以及业务方法自身抛出的任何业务错误
   */
  @org.springframework.transaction.annotation.Transactional
  public OperationResult execute(
      RequestContext context,
      Map<String, Object> body,
      AccessLevel access,
      boolean idempotent,
      java.util.function.Supplier<OperationResult> operation) {
    return run(context, body, access, idempotent, true, operation);
  }

  /**
   * 不加全局写锁的写事务，供已在事务外持有细粒度分布式锁的接口使用（目前只有预约下单）。
   *
   * <p>调用方必须自己保证并发安全：事务外先拿 {@link com.warmpaw.service.DistributedLock}，
   * 事务内对争用资源做乐观锁条件更新。鉴权、幂等回放与落库和 {@link #execute} 完全相同。
   */
  @org.springframework.transaction.annotation.Transactional
  public OperationResult executeWithoutGlobalLock(
      RequestContext context,
      Map<String, Object> body,
      AccessLevel access,
      boolean idempotent,
      java.util.function.Supplier<OperationResult> operation) {
    return run(context, body, access, idempotent, false, operation);
  }

  /**
   * 幂等作用域：{@code hash(actorId + method + path + key)}。
   *
   * <p>四段用换行分隔：否则 ("ab","c") 与 ("a","bc") 会拼出同一个作用域。
   * 公开出来是为了让 HTTP 层在事务外用同一个作用域做 Redis SETNX 占位。
   */
  public static String scope(RequestContext context) {
    return hash(
        context.actor().id()
            + "\n"
            + context.method()
            + "\n"
            + context.path()
            + "\n"
            + context.idempotencyKey());
  }

  /** 幂等键必须是 UUID 格式，否则 400。 */
  public static void requireIdempotencyKey(String key) {
    require(
        key != null
            && key.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
        400,
        "VALIDATION_ERROR",
        "请提供 UUID 格式的 Idempotency-Key");
  }

  /** 两个入口的公共实现；{@code globalLock} 决定写请求是否先取全局行锁。 */
  private OperationResult run(
      RequestContext context,
      Map<String, Object> body,
      AccessLevel access,
      boolean idempotent,
      boolean globalLock,
      java.util.function.Supplier<OperationResult> operation) {
    String path = context.path(), key = context.idempotencyKey();
    AuthService.Actor actor = context.actor();
    // admin 决定重放时给哪套视图；write 只看方法名——PUT/PATCH/DELETE 与 POST 一样要加锁。
    boolean admin = access == AccessLevel.ADMIN || access == AccessLevel.STAFF, write = !context.method().equals("GET");
    // 鉴权放在加锁之前：无权限的请求不该有机会排队占住全局锁，否则未授权流量也能把写吞吐拖死。
    access.check(context.actor());
    // 单店全局行锁，随本事务提交/回滚自动释放；只对写请求加。
    if (write && globalLock) store.lock();
    // 取得写锁后再次校验，等待期间被停用或重置密码的员工不能继续交付。
    if (write && (access == AccessLevel.ADMIN || access == AccessLevel.STAFF))
      auth.requireActiveSession(actor);
    boolean idem = write && idempotent;
    String scope = null;
    if (idem) {
      requireIdempotencyKey(key);
      scope = scope(context);
      Map<String, Object> old = store.replay(scope);
      if (old != null) {
        // 同一个键配不同请求体属于客户端复用错误，必须报错而不是回放旧结果。
        require(
            hash(canonical(body)).equals(old.get("body_hash")),
            409,
            "IDEMPOTENCY_CONFLICT",
            "同一幂等键的请求内容不同");
        return hooks.replay(
            read(old.get("response_body").toString()),
            path,
            admin,
            ((Number) old.get("http_status")).intValue());
      }
    }
    OperationResult reply = operation.get();
    // 记录 kind/resource 供 afterCommit 与重放重建视图；写入与业务在同一事务内。
    if (idem)
      store.remember(
          scope,
          hash(canonical(body)),
          reply.resource(),
          com.warmpaw.common.Json.write(
              map("data", reply.data(), "kind", reply.kind(), "resource", reply.resource())),
          reply.status(),
          Instant.now().toString());
    return reply;
  }

}
