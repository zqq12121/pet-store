package com.warmpaw.controller.support;

import static com.warmpaw.common.ApiException.require;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.BusinessOperationExecutor;
import com.warmpaw.application.OperationResult;
import com.warmpaw.application.RequestContext;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.GuestService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * HTTP 适配公共逻辑：解析身份、建立上下文，业务调用交给事务执行器。
 *
 * <p>本类是"Controller → 业务"之间唯一的一层胶水，职责只有四步：
 * 解身份 → 建 {@link RequestContext} → 交给 {@link BusinessOperationExecutor}（事务边界在这里）
 * → 调 {@link com.warmpaw.application.BusinessHooks#afterCommit} → 包成功响应。
 *
 * <p><b>事务边界的精确位置</b>：{@code operations.execute(...)} 是 {@code @Transactional}
 * 的代理方法，它在返回时就已提交。所以第 4 步的 afterCommit 是<b>真正在提交之后</b>执行的，
 * 而不是"提交前"。这也是把 afterCommit 放在这一层、而不是放进执行器内部的原因——
 * 放进去就还在事务里，外部调用会把全局锁拖住。
 *
 * <p><b>身份解析在事务之外</b>：{@code auth.identify} 会查会话表，此时没有事务也没有锁。
 * 这没问题——会话校验是只读的，而且真正的写保护靠执行器里的全局锁 + 持锁后复检。
 *
 * <p>不负责：路径路由、参数校验（在业务方法里）、错误映射（在异常处理器里）。
 */
@Component
public class ApiRequestExecutor {
  private final AuthService auth;
  private final GuestService guests;
  private final BusinessOperationExecutor operations;
  private final com.warmpaw.application.BusinessHooks hooks;
  public ApiRequestExecutor(AuthService auth, GuestService guests, BusinessOperationExecutor operations,
      com.warmpaw.application.BusinessHooks hooks) {
    this.auth = auth; this.guests = guests; this.operations = operations; this.hooks = hooks;
  }

  /**
   * 处理一次请求：解析身份 → 建上下文 → 事务内执行 → 事务外收尾 → 包装响应。
   *
   * <p><b>VISITOR 的互斥规则</b>：同时带 {@code Authorization} 与 {@code X-Guest-Token}
   * 会被判 400。因为"以谁的身份记账"必须唯一——否则一次操作可能既算买家的又算游客的，
   * 统计口径与权限判断都会歧义。带了登录身份就优先按买家处理，游客 token 必须去掉。
   *
   * <p>{@code actor} 为 null 表示匿名（公开接口），{@code AccessLevel.check} 会按级别决定
   * 是否放行；{@code actor == null} 本身不是错误。
   *
   * <p><b>路径只去掉 {@code /api/v1} 前缀</b>：这个值不参与路由，只参与幂等作用域。
   * 若部署时前缀不同（例如网关已剥离），作用域会随之变化——旧幂等键因此失效，
   * 但不会造成业务错误。{@code substring} 假设前缀存在，前缀不匹配时会切错位置，
   * 仍是"作用域不一致"而非崩溃。
   *
   * <p><b>{@code ip} 取 {@code getRemoteAddr()}</b>：在反向代理后面拿到的是代理地址，
   * 除非容器配置了 forwarded-headers 策略。它被用于限流与支付风控，
   * 部署在网关后时应确保 {@code server.forward-headers-strategy} 已正确设置。
   *
   * <p><b>afterCommit 的异常</b>：此时事务已提交，抛出的异常会变成 500，
   * 但业务数据已经生效——客户端重试需要幂等键来保证不会重复执行。
   */
  public ResponseEntity<Object> execute(
      HttpServletRequest request,
      Map<String, Object> body,
      AccessLevel access,
      boolean idempotent,
      Function<RequestContext, OperationResult> action) {
    AuthService.Actor actor = auth.identify(request.getHeader("Authorization"));
    if (access == AccessLevel.VISITOR) {
      require(
          actor == null || request.getHeader("X-Guest-Token") == null,
          400,
          "VALIDATION_ERROR",
          "买家与游客身份只能选一种");
      if (actor == null) actor = guests.identify(request.getHeader("X-Guest-Token"));
    }
    RequestContext context =
        new RequestContext(
            request.getMethod(),
            request.getRequestURI().substring("/api/v1".length()),
            request.getHeader("Idempotency-Key"),
            actor,
            request.getRemoteAddr());
    OperationResult result =
        operations.execute(context, body, access, idempotent, () -> action.apply(context));
    // execute 返回即事务已提交，此后才能安全地做外部调用（支付下单、发通知）。
    result = hooks.afterCommit(result, context);
    return ApiResponses.ok(result.status(), result.data());
  }
}
