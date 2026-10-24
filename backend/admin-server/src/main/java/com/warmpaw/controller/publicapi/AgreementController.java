package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AgreementApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 当前协议查询接口：仅适配 HTTP，具体业务在 AgreementApplicationService（调用方：公开）。
 *
 * <p>路由前缀 {@code /api/v1}，路径 {@code /agreements/current}。
 *
 * <p><b>权限模型：{@link AccessLevel#PUBLIC}</b>，完全不做身份校验——下单前必须能读到协议原文，
 * 否则用户无法在同意前知情。这里不区分买家/游客/管理端，也不读 Authorization 头。
 *
 * <p>委托 {@link AgreementApplicationService}；只返回已发布版本，草稿与已退役版本不对外。
 */
@RestController
@RequestMapping("/api/v1")
public class AgreementController {
  private final ApiRequestExecutor requests;
  private final AgreementApplicationService service;

  public AgreementController(ApiRequestExecutor requests, AgreementApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /agreements/current；访问级别：PUBLIC。
   *
   * <p>按 {@code type} 返回当前生效的协议：{@code live_pet_trade}（活体交易主协议，含健康保障天数）
   * 或 {@code pickup_confirmation}（现场确认文本）。{@code type} 缺失或取值非法返回 400。
   *
   * <p>响应含正文、{@code contentHash} 与 {@code publishedAt}：下单时把哈希一并写入订单，
   * 事后即可证明"签的是哪一版"，因此本接口的结果<b>必须与下单校验读取的版本一致</b>。
   *
   * <p>只读、幂等；无该类型的已发布版本时 404。
   */
  @GetMapping("/agreements/current")
  public ResponseEntity<Object> current(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.current(query));
  }
}
