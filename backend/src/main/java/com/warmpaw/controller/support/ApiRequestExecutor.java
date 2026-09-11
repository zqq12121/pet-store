package com.warmpaw.controller.support;

import static com.warmpaw.common.ApiException.require;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.BusinessOperationExecutor;
import com.warmpaw.application.OperationResult;
import com.warmpaw.application.RequestContext;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.GuestService;
import com.warmpaw.service.PaymentService;
import com.warmpaw.service.PaymentWorker;
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
  private final PaymentWorker worker;
  private final PaymentService payments;
  private final BusinessRepository store;

  public ApiRequestExecutor(
      AuthService auth,
      GuestService guests,
      BusinessOperationExecutor operations,
      PaymentWorker worker,
      PaymentService payments,
      BusinessRepository store) {
    this.auth = auth;
    this.guests = guests;
    this.operations = operations;
    this.worker = worker;
    this.payments = payments;
    this.store = store;
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
    // 先提交支付意图，再调用外部支付平台，避免网络故障回滚已创建的支付单。
    if ("payment".equals(result.kind()) && "POST".equals(request.getMethod())) {
      worker.processPayment(result.resource(), context.ip());
      result =
          new OperationResult(
              result.status(), payments.view(store.get("payment", result.resource())));
    }
    return ApiResponses.ok(result.status(), result.data());
  }
}
