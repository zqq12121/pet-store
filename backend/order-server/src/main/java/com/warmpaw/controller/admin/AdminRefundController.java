package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.PaymentApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台退款重试接口：仅适配 HTTP，具体业务在 PaymentApplicationService。
 *
 * <p><b>调用方</b>：门店后台的人工补救流程。路由前缀 {@code /api/v1}，
 * 端点为 {@code /admin/refunds/{refundId}/retry}。
 *
 * <p><b>权限模型</b>：{@link AccessLevel#ADMIN}，即 {@code AuthService.role(actor, "admin")}。
 * 动的是资金，不放给 staff。
 *
 * <p>业务委托 {@link PaymentApplicationService#retryRefund}，注意它还额外要求运行在本地模式，
 * 线上会以 409 拒绝——这一点与权限无关，是业务约束。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminRefundController {
  private final ApiRequestExecutor requests;
  private final PaymentApplicationService service;

  public AdminRefundController(ApiRequestExecutor requests, PaymentApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * POST /admin/refunds/{refundId}/retry；访问级别：ADMIN。
   *
   * <p>把一笔 {@code failed} 的退款重新置为 {@code processing}，交给退款工作器再发一次。
   * 请求体必填 {@code reason}（1~500 字），用于审计——重试资金操作必须能回答"谁、为什么"。
   *
   * <p><b>状态码</b>：成功 202 而不是 200，因为这里只改了本地意图，真正打款仍是异步的；
   * 退款单状态不是 {@code failed} 返回 409，非本地环境也返回 409（需先查平台侧真实状态，
   * 盲目重发可能掩盖对账缺口）。
   *
   * <p>写操作，{@code idempotent=true}，需带 {@code Idempotency-Key}，
   * 防止重复点击造成多次重试记录。
   */
  @PostMapping("/admin/refunds/{refundId}/retry")
  public ResponseEntity<Object> retry(
      @PathVariable String refundId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request, body, AccessLevel.ADMIN, true, context -> service.retryRefund(refundId, body));
  }
}
