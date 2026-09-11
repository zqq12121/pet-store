package com.warmpaw.controller.buyer;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OrderApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 买家订单接口：仅适配 HTTP，具体业务在 OrderApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {
  private final ApiRequestExecutor requests;
  private final OrderApplicationService service;

  public OrderController(ApiRequestExecutor requests, OrderApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** POST /orders/preview；访问级别：BUYER。 */
  @PostMapping("/orders/preview")
  public ResponseEntity<Object> preview(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request, body, AccessLevel.BUYER, false, context -> service.preview(body));
  }

  /** POST /orders；访问级别：BUYER。 */
  @PostMapping("/orders")
  public ResponseEntity<Object> create(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request, body, AccessLevel.BUYER, true, context -> service.create(body, context.actor()));
  }

  /** GET /orders；访问级别：BUYER。 */
  @GetMapping("/orders")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.list(query, context.actor()));
  }

  /** GET /orders/{orderId}；访问级别：BUYER。 */
  @GetMapping("/orders/{orderId}")
  public ResponseEntity<Object> detail(@PathVariable String orderId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.detail(orderId, context.actor()));
  }

  /** GET /orders/{orderId}/payment；访问级别：BUYER。 */
  @GetMapping("/orders/{orderId}/payment")
  public ResponseEntity<Object> paymentStatus(
      @PathVariable String orderId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.paymentStatus(orderId, context.actor()));
  }

  /** POST /orders/{orderId}/cancel；访问级别：BUYER。 */
  @PostMapping("/orders/{orderId}/cancel")
  public ResponseEntity<Object> cancel(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.cancel(orderId, body, context.actor()));
  }

  /** POST /orders/{orderId}/payments；访问级别：BUYER。 */
  @PostMapping("/orders/{orderId}/payments")
  public ResponseEntity<Object> beginPayment(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.beginPayment(orderId, body, context.actor()));
  }

  /** POST /orders/{orderId}/pickup-verification-codes；访问级别：BUYER。 */
  @PostMapping("/orders/{orderId}/pickup-verification-codes")
  public ResponseEntity<Object> pickupCode(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        false,
        context -> service.pickupCode(orderId, body, context.actor(), context.ip()));
  }

  /** POST /orders/{orderId}/pickup-confirmations；访问级别：BUYER。 */
  @PostMapping("/orders/{orderId}/pickup-confirmations")
  public ResponseEntity<Object> confirmPickup(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.confirmPickup(orderId, body, context.actor()));
  }
}
