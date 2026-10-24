package com.warmpaw.controller.dev;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OrderApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 本地模拟支付接口：仅适配 HTTP，具体业务在 OrderApplicationService。
 *
 * <p><b>调用方</b>：本地开发/演示环境的前端。路由前缀 {@code /api/v1}，同时挂
 * {@code /dev} 与 {@code /demo} 两个别名（{@code /demo} 给演示脚本用）。
 *
 * <p><b>权限模型</b>：{@link AccessLevel#BUYER}，即 {@code AuthService.role(actor, "buyer")}。
 * 这点常被误解——调试接口并不是公开的：它仍要求登录，并且 service 会再过
 * {@code orders.owned}，只能模拟自己订单的支付。
 *
 * <p><b>环境开关</b>：service 里用 {@code auth.local()}（即 mock 模式）判定，非本地环境返回 404
 * 而不是 403——这个接口在生产"不存在"，不该让人知道系统里有这样一个后门。
 *
 * <p>业务委托 {@link OrderApplicationService#simulatePayment}，走的仍是真实链路
 * （{@code begin} + {@code paid(mockFact)}），所以金额校验、自提码生成等逻辑不会被跳过。
 */
@RestController
@RequestMapping("/api/v1")
public class LocalPaymentController {
  private final ApiRequestExecutor requests;
  private final OrderApplicationService service;

  public LocalPaymentController(ApiRequestExecutor requests, OrderApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * POST /dev/orders/{orderId}/pay（别名 /demo/orders/{orderId}/pay）；访问级别：BUYER。
   *
   * <p>把指定订单模拟为"已支付"，用于本地联调自提码、核销、售后等后续流程。
   * 两个路径指向同一处理器，是为了让演示脚本不必区分环境。
   *
   * <p>前置条件：仅本地模式；订单必须属于当前买家且处于 {@code pending_paid}、未过期。
   * 否则 404（接口或订单"不存在"）/ 409 {@code ORDER_EXPIRED}。
   *
   * <p>成功 200 并返回刷新后的买家视图。{@code idempotent=false}：
   * 这是调试入口，重复执行不会造成资金错误——第二次会因状态已变而 409，
   * 不需要引入幂等键的管理成本。
   */
  @PostMapping({"/dev/orders/{orderId}/pay", "/demo/orders/{orderId}/pay"})
  public ResponseEntity<Object> simulate(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        false,
        context -> service.simulatePayment(orderId, body, context.actor()));
  }
}
