package com.warmpaw.controller.dev;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OrderApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 本地模拟支付接口：仅适配 HTTP，具体业务在 OrderApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class LocalPaymentController {
  private final ApiRequestExecutor requests;
  private final OrderApplicationService service;

  public LocalPaymentController(ApiRequestExecutor requests, OrderApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** POST /dev/orders/{orderId}/pay；访问级别：BUYER。 */
  @PostMapping({"/dev/orders/{orderId}/pay", "/demo/orders/{orderId}/pay"})
  public ResponseEntity<Object> simulate(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        false,
        context -> service.simulatePayment(orderId, body, context.actor()));
  }
}
