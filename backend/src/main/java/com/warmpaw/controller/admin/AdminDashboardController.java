package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AnalyticsApplicationService;
import com.warmpaw.application.OperationResult;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台经营统计接口：仅适配 HTTP，具体业务在 AnalyticsApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AdminDashboardController {
  private final ApiRequestExecutor requests;
  private final AnalyticsApplicationService service;

  public AdminDashboardController(
      ApiRequestExecutor requests, AnalyticsApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /admin/dashboard；访问级别：ADMIN。 */
  @GetMapping("/admin/dashboard")
  public ResponseEntity<Object> dashboard(HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.ADMIN,
        false,
        context -> new OperationResult(200, service.dashboard()));
  }
}
