package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AgreementApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台协议管理接口：仅适配 HTTP，具体业务在 AgreementApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AdminAgreementController {
  private final ApiRequestExecutor requests;
  private final AgreementApplicationService service;

  public AdminAgreementController(
      ApiRequestExecutor requests, AgreementApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /admin/agreements；访问级别：ADMIN。 */
  @GetMapping("/admin/agreements")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.list(query));
  }

  /** GET /admin/agreements/{agreementId}；访问级别：ADMIN。 */
  @GetMapping("/admin/agreements/{agreementId}")
  public ResponseEntity<Object> detail(
      @PathVariable String agreementId, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.detail(agreementId));
  }

  /** POST /admin/agreements；访问级别：ADMIN。 */
  @PostMapping("/admin/agreements")
  public ResponseEntity<Object> create(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request, body, AccessLevel.ADMIN, false, context -> service.create(body, context.actor()));
  }

  /** POST /admin/agreements/{agreementId}/publish；访问级别：ADMIN。 */
  @PostMapping("/admin/agreements/{agreementId}/publish")
  public ResponseEntity<Object> publish(
      @PathVariable String agreementId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.publish(agreementId, body, context.actor()));
  }
}
