package com.warmpaw.controller.buyer;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AfterSaleApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 买家售后接口：仅适配 HTTP，具体业务在 AfterSaleApplicationService。
 *
 * <p><b>调用方</b>：买家小程序/H5。路由前缀 {@code /api/v1}，路径分为"挂在订单下"
 * （{@code /orders/{orderId}/after-sales}）与"直接访问售后单"（{@code /after-sales/{saleId}}）两类。
 *
 * <p><b>权限模型</b>：全部 {@link AccessLevel#BUYER}，即 {@code AuthService.role(actor, "buyer")}。
 * 在这一层之上，service 还会用 {@code sales.owned(orderId/saleId, actor)} 校验数据归属；
 * 非本人且非店长一律 404（不是 403），避免泄露"这个 id 是否存在"。
 *
 * <p><b>幂等</b>：申请售后、确认换宠是写操作，{@code idempotent=true}，需带 UUID 格式
 * {@code Idempotency-Key}；两个读接口和发短信接口不要求。
 */
@RestController
@RequestMapping("/api/v1")
public class AfterSaleController {
  private final ApiRequestExecutor requests;
  private final AfterSaleApplicationService service;

  public AfterSaleController(ApiRequestExecutor requests, AfterSaleApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /orders/{orderId}/after-sales；访问级别：BUYER。
   *
   * <p>某订单下的售后记录列表（概要字段，不含材料与时间线），支持 {@code page/pageSize}。
   * 先校验订单归属再查售后，订单不属于当前买家时返回 404 而不是空列表——
   * 否则可以通过"空/非空"判断他人订单是否存在。
   *
   * <p>只读，不要求幂等键，固定 200。
   */
  @GetMapping("/orders/{orderId}/after-sales")
  public ResponseEntity<Object> listForOrder(
      @PathVariable String orderId,
      @RequestParam Map<String, Object> query,
      HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.listForOrder(orderId, query, context.actor()));
  }

  /**
   * POST /orders/{orderId}/after-sales；访问级别：BUYER。
   *
   * <p>提交售后申请。请求体需带 {@code type}（{@code refund_before_pickup} / {@code health_issue}）、
   * {@code requestedResolution}（全额退 / 换宠 / 分担治疗费）、{@code reason}，
   * 健康问题还需 {@code diagnosisAt} 与诊断材料 id。可申请的取值由
   * {@code OrderService.eligibility} 推导：类型不在窗口内返回 422 {@code AFTER_SALE_WINDOW_EXPIRED}，
   * 处理方式不匹配返回 400，已有进行中的售后返回 409。
   *
   * <p>成功 201：这是一次资源创建，响应头/体里的 id 即新售后单。创建后会阻断该订单的交付，
   * 所以 {@code idempotent=true} 且必须带 {@code Idempotency-Key}，避免重复提交产生两条售后单。
   */
  @PostMapping("/orders/{orderId}/after-sales")
  public ResponseEntity<Object> create(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.create(orderId, body, context.actor()));
  }

  /**
   * GET /after-sales/{saleId}；访问级别：BUYER。
   *
   * <p>买家查看自己的售后单详情（含审核结论、时间线、换宠进度）。
   * 归属校验在 service 的 {@code sales.owned}：非本人返回 404，防止拿到别人的售后编号就能翻材料。
   *
   * <p>只读，固定 200，不要求幂等键。
   */
  @GetMapping("/after-sales/{saleId}")
  public ResponseEntity<Object> detail(@PathVariable String saleId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.detail(saleId, context.actor()));
  }

  /**
   * POST /after-sales/{saleId}/exchange-verification-codes；访问级别：BUYER。
   *
   * <p>为"确认换宠"发送短信验证码。收件人取登录账号绑定手机号（不是订单联系人），
   * 因为换宠只针对注册买家，避免验证码被手填号码劫持；验证码与具体售后单 + 替换宠绑定。
   *
   * <p>为什么 {@code idempotent=false}：接口本身有外部副作用（真的发一条短信），
   * 把结果缓存下来会让用户第二次点"发送"收不到码。防刷靠 service 里的 IP 限流，
   * 所以必须把 {@code context.ip()} 传进去。
   *
   * <p>成功 200，返回 {@code smsRequestId} 与有效期；后续确认换宠时需原样回传。
   */
  @PostMapping("/after-sales/{saleId}/exchange-verification-codes")
  public ResponseEntity<Object> exchangeCode(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        false,
        context -> service.exchangeCode(saleId, body, context.actor(), context.ip()));
  }

  /**
   * POST /after-sales/{saleId}/exchange-confirmations；访问级别：BUYER。
   *
   * <p>买家现场确认替换宠：请求体带 {@code replacementPetId}（必须与店长当前选定的替换宠一致，
   * 否则 409 {@code VERSION_CONFLICT}）、确认文本版本与哈希、四项健康检查、短信验证码。
   * 校验通过后生成一条带有效期的确认记录，供店长"交付换宠"时比对 {@code confirmationId}。
   *
   * <p>成功 200（确认记录附属于售后单，不作为独立资源返回 201）。
   * 写操作，{@code idempotent=true}，需带 {@code Idempotency-Key}：
   * 重复提交会让同一售后单产生多条确认记录，必须靠幂等键收敛。
   */
  @PostMapping("/after-sales/{saleId}/exchange-confirmations")
  public ResponseEntity<Object> confirmExchange(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.confirmExchange(saleId, body, context.actor()));
  }
}
