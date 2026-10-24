package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OrderApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台到店核销接口：仅适配 HTTP，具体业务在 OrderApplicationService。
 *
 * <p><b>调用方</b>：门店员工在柜台扫码/输码核销。路由前缀 {@code /api/v1}，方法内声明
 * {@code /admin/pickups...} 与 {@code /admin/orders/{id}/pickup}。
 *
 * <p><b>权限模型</b>：两个端点都是 {@link AccessLevel#STAFF}，即 {@code AuthService.staff}——
 * 店长与员工都可核销，这是门店日常最高频的动作。员工不得借此替换检疫材料，
 * 该限制由 {@code OrderApplicationService#pickup} 的字段白名单落实。
 *
 * <p>业务委托 {@link OrderApplicationService}（内部再调 {@code OrderService}）。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminPickupController {
  private final ApiRequestExecutor requests;
  private final OrderApplicationService service;

  public AdminPickupController(ApiRequestExecutor requests, OrderApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * POST /admin/pickups/lookup；访问级别：STAFF。
   *
   * <p>店员凭 {@code pickupCode} 或 {@code qrPayload}（二选一，异或约束）查单，
   * 返回订单摘要、买家确认状态与 {@code pickupAllowed} 标记。
   *
   * <p>为什么是 POST 而不是 GET：凭证不能出现在 URL 上（会被访问日志、浏览器历史记录留存），
   * 必须走请求体。
   *
   * <p>为什么 {@code idempotent=false}：这是查询，只是借用了 POST 的报文格式；
   * 不写幂等记录，也就不需要 {@code Idempotency-Key}。
   *
   * <p>状态码：命中固定 200（即便当前不可交付也返回 200 + 原因），凭证无效 404。
   * 同一员工 60 秒内连续查不到 5 次会被限流返回 429——8 位纯数字码理论上可枚举。
   */
  @PostMapping("/admin/pickups/lookup")
  public ResponseEntity<Object> lookup(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.STAFF,
        false,
        context -> service.lookupPickup(body, context.actor()));
  }

  /**
   * POST /admin/orders/{orderId}/pickup；访问级别：STAFF。
   *
   * <p>完成交付（核销）：请求体需带 {@code confirmationId}（必须与买家现场确认单一致）、
   * {@code pickupCode} 或 {@code qrPayload} 之一、{@code quarantineVerified=true}。
   * 全部通过后订单转 {@code completed}、宠物转 {@code sold}、自提码立即清空。
   *
   * <p>员工身份下 service 会收紧字段白名单，禁止传 {@code deliveryQuarantine}：
   * 检疫材料只能由店长上传核验，否则等于绕过整套检疫校验。
   *
   * <p>写操作，{@code idempotent=true}，需带 {@code Idempotency-Key}。交付是终态动作，
   * 网络重试必须回放首次结果而不能二次核销。成功 200（改既有订单，不新建资源）。
   */
  @PostMapping("/admin/orders/{orderId}/pickup")
  public ResponseEntity<Object> pickup(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.STAFF,
        true,
        context -> service.pickup(orderId, body, context.actor()));
  }
}
