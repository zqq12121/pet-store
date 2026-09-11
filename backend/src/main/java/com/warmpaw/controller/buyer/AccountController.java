package com.warmpaw.controller.buyer;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AccountApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 买家资料接口：仅适配 HTTP，具体业务在 AccountApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {
  private final ApiRequestExecutor requests;
  private final AccountApplicationService service;

  public AccountController(ApiRequestExecutor requests, AccountApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /me；访问级别：BUYER。 */
  @GetMapping("/me")
  public ResponseEntity<Object> profile(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.BUYER, false, context -> service.profile(context.actor()));
  }

  /** PATCH /me；访问级别：BUYER。 */
  @PatchMapping("/me")
  public ResponseEntity<Object> update(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        false,
        context -> service.updateProfile(body, context.actor()));
  }
}
