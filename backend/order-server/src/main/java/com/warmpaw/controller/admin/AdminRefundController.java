package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.PaymentApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台退款重试接口：仅适配 HTTP，具体业务在 PaymentApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AdminRefundController {
  private final ApiRequestExecutor requests;
  private final PaymentApplicationService service;

  public AdminRefundController(ApiRequestExecutor requests, PaymentApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** POST /admin/refunds/{refundId}/retry；访问级别：ADMIN。 */
  @PostMapping("/admin/refunds/{refundId}/retry")
  public ResponseEntity<Object> retry(
      @PathVariable String refundId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request, body, AccessLevel.ADMIN, true, context -> service.retryRefund(refundId, body));
  }
}
