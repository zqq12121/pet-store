package com.warmpaw.controller.buyer;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AfterSaleApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 买家售后接口：仅适配 HTTP，具体业务在 AfterSaleApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AfterSaleController {
  private final ApiRequestExecutor requests;
  private final AfterSaleApplicationService service;

  public AfterSaleController(ApiRequestExecutor requests, AfterSaleApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /orders/{orderId}/after-sales；访问级别：BUYER。 */
  @GetMapping("/orders/{orderId}/after-sales")
  public ResponseEntity<Object> listForOrder(
      @PathVariable String orderId,
      @RequestParam Map<String, Object> query,
      HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.listForOrder(orderId, query, context.actor()));
  }

  /** POST /orders/{orderId}/after-sales；访问级别：BUYER。 */
  @PostMapping("/orders/{orderId}/after-sales")
  public ResponseEntity<Object> create(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.create(orderId, body, context.actor()));
  }

  /** GET /after-sales/{saleId}；访问级别：BUYER。 */
  @GetMapping("/after-sales/{saleId}")
  public ResponseEntity<Object> detail(@PathVariable String saleId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.detail(saleId, context.actor()));
  }

  /** POST /after-sales/{saleId}/exchange-verification-codes；访问级别：BUYER。 */
  @PostMapping("/after-sales/{saleId}/exchange-verification-codes")
  public ResponseEntity<Object> exchangeCode(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        false,
        context -> service.exchangeCode(saleId, body, context.actor(), context.ip()));
  }

  /** POST /after-sales/{saleId}/exchange-confirmations；访问级别：BUYER。 */
  @PostMapping("/after-sales/{saleId}/exchange-confirmations")
  public ResponseEntity<Object> confirmExchange(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.confirmExchange(saleId, body, context.actor()));
  }
}
