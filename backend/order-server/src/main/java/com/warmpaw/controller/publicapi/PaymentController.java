package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.PaymentApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 支付能力查询接口：仅适配 HTTP，具体业务在 PaymentApplicationService。
 *
 * <p><b>调用方</b>：任何前端页面（含未登录的落地页）。路由前缀 {@code /api/v1}，
 * 端点为 {@code /payment-capabilities}。
 *
 * <p><b>权限模型</b>：{@link AccessLevel#PUBLIC}，即 {@code AuthService} 不做任何校验，
 * 匿名请求也放行——前端需要在渲染结算按钮之前就知道线上支付是否可用，此时可能还没有登录态。
 * 这里只暴露"能力开关"，不含任何订单或个人数据。
 *
 * <p>业务委托 {@link PaymentApplicationService#capabilities}。
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {
  private final ApiRequestExecutor requests;
  private final PaymentApplicationService service;

  public PaymentController(ApiRequestExecutor requests, PaymentApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /payment-capabilities；访问级别：PUBLIC。
   *
   * <p>返回币种与各支付场景（{@code jsapi}/{@code h5}）的 {@code enabled} 及 {@code disabledReason}
   * ——当前线上支付全部关闭，并统一说明"请预约到店付款"。前端据此决定是否渲染"立即支付"，
   * 避免点了才收到 409。
   *
   * <p>纯读、无参数、不写幂等记录，固定 200；匿名可访问，因此响应里不放商户号、
   * 密钥指纹等任何内部配置。
   */
  @GetMapping("/payment-capabilities")
  public ResponseEntity<Object> capabilities(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.capabilities());
  }
}
