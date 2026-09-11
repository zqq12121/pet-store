package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AnalyticsApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 访问事件上报接口：仅适配 HTTP，具体业务在 AnalyticsApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {
  private final ApiRequestExecutor requests;
  private final AnalyticsApplicationService service;

  public AnalyticsController(ApiRequestExecutor requests, AnalyticsApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** POST /analytics/events；访问级别：VISITOR。 */
  @PostMapping("/analytics/events")
  public ResponseEntity<Object> recordEvents(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.VISITOR,
        false,
        context -> service.recordEvents(body, context.actor()));
  }
}
