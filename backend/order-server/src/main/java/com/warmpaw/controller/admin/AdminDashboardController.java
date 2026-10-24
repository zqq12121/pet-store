package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.TransactionDashboardService;
import com.warmpaw.application.OperationResult;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台经营统计接口：仅适配 HTTP，具体业务在 TransactionDashboardService。
 *
 * <p><b>调用方</b>：门店管理端首页看板。路由前缀 {@code /api/v1}，方法内部声明 {@code /admin/dashboard}。
 *
 * <p><b>权限模型</b>：{@link AccessLevel#ADMIN}，即 {@code AuthService.role(actor, "admin")}。
 * 看板会汇总成交额、订单量等经营数据，不下发给员工账号。
 *
 * <p>只有一个只读端点，因此整个类不涉及幂等键。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminDashboardController {
  private final ApiRequestExecutor requests;
  private final TransactionDashboardService service;

  public AdminDashboardController(
      ApiRequestExecutor requests, TransactionDashboardService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /admin/dashboard；访问级别：ADMIN。
   *
   * <p>返回当日经营看板：营业额（线上流水 + 线下收款）、退款额、净现金、订单与在售数量，
   * 以及待确认预约、待自提、待审核售后等各项待办计数。纯读、无请求参数，固定 200。
   *
   * <p>这里手工包一层 {@code new OperationResult(200, ...)}，因为 service 直接返回 Map
   * 而不是 OperationResult——控制器负责补上状态码，业务层不必知道 HTTP。
   */
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
