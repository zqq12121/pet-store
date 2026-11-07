package com.warmpaw.controller.admin;

import static com.warmpaw.common.Json.map;
import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.MaintenanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 店长手动触发的维护操作。
 *
 * <p>不走 {@code ApiRequestExecutor}：它会把动作包进业务事务，而清理要求先在事务外拿分布式锁
 * （见 {@link MaintenanceService}）。所以这里直接校验店长身份，再调用清理。
 */
@RestController
@RequestMapping("/api/v1/admin/maintenance")
public class AdminMaintenanceController {
  private final AuthService auth;
  private final MaintenanceService maintenance;
  private final BusinessRepository store;

  public AdminMaintenanceController(
      AuthService auth, MaintenanceService maintenance, BusinessRepository store) {
    this.auth = auth;
    this.maintenance = maintenance;
    this.store = store;
  }

  /**
   * POST /admin/maintenance/file-cleanup：立即清理一次未被引用的文件，仅店长可用。
   *
   * <p>判据与每日任务相同（ready、超过 24 小时、未被引用、不属于订单附件），删除不可逆。
   *
   * @return 200 与本次清理数量 {@code cleaned}；另一轮正在进行时 409
   */
  @PostMapping("/file-cleanup")
  public ResponseEntity<Object> cleanFiles(HttpServletRequest request) {
    AuthService.Actor admin = auth.identify(request.getHeader("Authorization"));
    AuthService.role(admin, "admin");
    int cleaned = maintenance.cleanNow();
    store.audit(admin.id(), "file.cleanup", "cleaned:" + cleaned);
    return ok(200, map("cleaned", cleaned));
  }
}
