package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AgreementApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 当前协议查询接口：仅适配 HTTP，具体业务在 AgreementApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AgreementController {
  private final ApiRequestExecutor requests;
  private final AgreementApplicationService service;

  public AgreementController(ApiRequestExecutor requests, AgreementApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /agreements/current；访问级别：PUBLIC。 */
  @GetMapping("/agreements/current")
  public ResponseEntity<Object> current(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.current(query));
  }
}
