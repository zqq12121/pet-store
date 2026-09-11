package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AfterSaleApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台售后处理接口：仅适配 HTTP，具体业务在 AfterSaleApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AdminAfterSaleController {
  private final ApiRequestExecutor requests;
  private final AfterSaleApplicationService service;

  public AdminAfterSaleController(
      ApiRequestExecutor requests, AfterSaleApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /admin/after-sales；访问级别：ADMIN。 */
  @GetMapping("/admin/after-sales")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.adminList(query));
  }

  /** GET /admin/after-sales/{saleId}；访问级别：ADMIN。 */
  @GetMapping("/admin/after-sales/{saleId}")
  public ResponseEntity<Object> detail(@PathVariable String saleId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.ADMIN,
        false,
        context -> service.adminDetail(saleId, context.actor()));
  }

  /** PUT /admin/after-sales/{saleId}/exchange；访问级别：ADMIN。 */
  @PutMapping("/admin/after-sales/{saleId}/exchange")
  public ResponseEntity<Object> exchange(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.exchange(saleId, body, context.actor()));
  }

  /** POST /admin/after-sales/{saleId}/review；访问级别：ADMIN。 */
  @PostMapping("/admin/after-sales/{saleId}/review")
  public ResponseEntity<Object> review(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.review(saleId, body, context.actor()));
  }

  /** POST /admin/after-sales/{saleId}/returns；访问级别：ADMIN。 */
  @PostMapping("/admin/after-sales/{saleId}/returns")
  public ResponseEntity<Object> receiveReturn(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.receiveReturn(saleId, body, context.actor()));
  }

  /** POST /admin/after-sales/{saleId}/exchange-delivery；访问级别：ADMIN。 */
  @PostMapping("/admin/after-sales/{saleId}/exchange-delivery")
  public ResponseEntity<Object> deliverExchange(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.deliverExchange(saleId, body, context.actor()));
  }

  /** POST /admin/after-sales/{saleId}/exchange-refund；访问级别：ADMIN。 */
  @PostMapping("/admin/after-sales/{saleId}/exchange-refund")
  public ResponseEntity<Object> refundExchange(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.refundExchange(saleId, body, context.actor()));
  }
}
