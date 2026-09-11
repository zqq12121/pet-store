package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.PaymentApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 支付能力查询接口：仅适配 HTTP，具体业务在 PaymentApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final ApiRequestExecutor requests;
  private final PaymentApplicationService service;

  public PaymentController(ApiRequestExecutor requests, PaymentApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /payment-capabilities；访问级别：PUBLIC。 */
  @GetMapping("/payment-capabilities")
  public ResponseEntity<Object> capabilities(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.capabilities());
  }
}
