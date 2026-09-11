package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OrderApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台到店核销接口：仅适配 HTTP，具体业务在 OrderApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AdminPickupController {
  private final ApiRequestExecutor requests;
  private final OrderApplicationService service;

  public AdminPickupController(ApiRequestExecutor requests, OrderApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** POST /admin/pickups/lookup；访问级别：ADMIN。 */
  @PostMapping("/admin/pickups/lookup")
  public ResponseEntity<Object> lookup(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.lookupPickup(body, context.actor()));
  }

  /** POST /admin/orders/{orderId}/pickup；访问级别：ADMIN。 */
  @PostMapping("/admin/orders/{orderId}/pickup")
  public ResponseEntity<Object> pickup(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.pickup(orderId, body, context.actor()));
  }
}
