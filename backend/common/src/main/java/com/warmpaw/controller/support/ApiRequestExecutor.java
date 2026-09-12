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

/** HTTP 适配公共逻辑：解析身份、建立上下文，业务调用交给事务执行器。 */
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
    result = hooks.afterCommit(result, context);
    return ApiResponses.ok(result.status(), result.data());
  }
}
