package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OrderApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台订单查询接口：仅适配 HTTP，具体业务在 OrderApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AdminOrderController {
  private final ApiRequestExecutor requests;
  private final OrderApplicationService service;

  public AdminOrderController(ApiRequestExecutor requests, OrderApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** 预约确认、取消、线下收款交付：员工仅允许登记完成，确认和取消由店长操作。 */
  @PostMapping("/admin/orders/{orderId}/appointment/{action}")
  public ResponseEntity<Object> appointment(@PathVariable String orderId, @PathVariable String action,
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
    return requests.execute(request, body, "complete".equals(action) ? AccessLevel.STAFF : AccessLevel.ADMIN, true,
        context -> service.appointmentAction(orderId, action, body, context.actor()));
  }

  /** GET /admin/orders；访问级别：STAFF。 */
  @GetMapping("/admin/orders")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.STAFF,
        false,
        context -> service.adminList(query, context.actor()));
  }

  /** GET /admin/orders/{orderId}；访问级别：STAFF。 */
  @GetMapping("/admin/orders/{orderId}")
  public ResponseEntity<Object> detail(@PathVariable String orderId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.STAFF,
        false,
        context -> service.adminDetail(orderId, context.actor()));
  }
}
