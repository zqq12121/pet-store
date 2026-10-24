package com.warmpaw.controller.buyer;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OrderApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 买家订单接口：仅适配 HTTP，具体业务在 OrderApplicationService。
 *
 * <p><b>调用方</b>：买家小程序/H5。路由前缀 {@code /api/v1}，资源根为 {@code /orders}。
 *
 * <p><b>权限模型</b>：全部 {@link AccessLevel#BUYER}，即 {@code AuthService.role(actor, "buyer")}；
 * 游客身份（{@code VISITOR}）不能下单，只有浏览类接口才用 VISITOR。
 * 涉及具体订单的端点还会在 service 里过 {@code orders.owned(orderId, actor)}，
 * 非本人返回 404——不区分"不存在"和"没权限"，避免订单 id 被枚举。
 *
 * <p><b>幂等</b>：下单、取消、发起支付、现场确认是 {@code idempotent=true}，需带 UUID 格式
 * {@code Idempotency-Key}；预览、列表、详情、查支付状态、发短信为 false。
 *
 * <p>注意本服务当前只支持"到店预约"：{@link #create} 实际创建预约单，
 * {@link #beginPayment} 固定返回 409，保留端点只为兼容历史订单的旧前端。
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {
  private final ApiRequestExecutor requests;
  private final OrderApplicationService service;

  public OrderController(ApiRequestExecutor requests, OrderApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * POST /orders/preview；访问级别：BUYER。
   *
   * <p>结算页预览：请求体只需 {@code petId}，返回商品卡片、当前价格、协议版本与内容哈希、
   * 门店信息、支付超时与自提保留时长、以及 {@code purchaseAllowed} 与阻断原因。
   *
   * <p>用 POST 是因为 {@code petId} 属于业务参数、且未来可能扩展为多商品；用
   * {@code idempotent=false} 是因为它纯读、不加锁、不落库——预览不该写入幂等表。
   *
   * <p>固定 200；宠物已下架返回 404（不向买家暴露草稿）。售出/预订中仍返回 200，
   * 只是 {@code purchaseAllowed=false}，前端据此禁用下单按钮。
   */
  @PostMapping("/orders/preview")
  public ResponseEntity<Object> preview(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request, body, AccessLevel.BUYER, false, context -> service.preview(body));
  }

  /**
   * POST /orders；访问级别：BUYER。
   *
   * <p>下单——<b>实际创建的是到店预约</b>（委托 {@code AppointmentService}），
   * 不发起微信支付，付款在到店交付时线下完成。请求体需带 {@code petId}、{@code productVersion}、
   * {@code expectedAmount}、联系人信息、协议版本/哈希、{@code agreementAccepted=true}、{@code visitAt}。
   *
   * <p>字段回传是刻意的：服务端拿 {@code expectedAmount}/{@code productVersion}/{@code agreementVersion}
   * 做三重一致性校验，防止"页面显示旧价格、下单按新价格成交"。
   *
   * <p>成功 201 且响应体是买家视图（{@code detail(order,false)}），不是领域对象原样返回，
   * 避免下发 {@code ownerId} 等内部字段。{@code idempotent=true}，必须带 {@code Idempotency-Key}：
   * 下单会占用"一宠一锁"，重复提交必须回放首次结果而不能生成第二笔预约。
   */
  @PostMapping("/orders")
  public ResponseEntity<Object> create(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request, body, AccessLevel.BUYER, true, context -> service.create(body, context.actor()));
  }

  /**
   * GET /orders；访问级别：BUYER。
   *
   * <p>"我的订单"列表，客户端只允许传 {@code tab/page/pageSize}：买家白名单刻意比后台窄，
   * 不允许按手机号或订单号跨人搜索。{@code tab} 把多个内部状态聚合成一个用户语义
   * （{@code pending_pickup}、{@code after_sale} 等），非法值返回 400。
   *
   * <p>只返回摘要 + 服务端算好的 {@code availableActions}，前端不再维护一套状态判断。只读，固定 200。
   */
  @GetMapping("/orders")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.list(query, context.actor()));
  }

  /**
   * GET /orders/{orderId}；访问级别：BUYER。
   *
   * <p>买家订单详情：摘要的超集，含价格/商品快照、协议签署内容、售后资格、退款记录。
   * 手机号只给掩码（{@code contactPhoneMasked}），{@code pickup} 自提码仅在"待自提且无售后阻断"
   * 时下发，避免已完成订单继续暴露取货凭证。
   *
   * <p>非本人订单 404。只读，固定 200，不要求幂等键。
   */
  @GetMapping("/orders/{orderId}")
  public ResponseEntity<Object> detail(@PathVariable String orderId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.detail(orderId, context.actor()));
  }

  /**
   * GET /orders/{orderId}/payment；访问级别：BUYER。
   *
   * <p>支付状态查询。当前支付入口已下线，这个端点只服务历史订单：老单仍可能需要确认
   * "到底付没付、要不要退款"，所以不能直接删掉。归属校验同详情，非本人 404。
   *
   * <p>只读、幂等、固定 200。
   */
  @GetMapping("/orders/{orderId}/payment")
  public ResponseEntity<Object> paymentStatus(
      @PathVariable String orderId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.BUYER,
        false,
        context -> service.paymentStatus(orderId, context.actor()));
  }

  /**
   * POST /orders/{orderId}/cancel；访问级别：BUYER。
   *
   * <p>买家取消订单/预约：预约单直接转 {@code cancelled} 并释放宠物库存；
   * 历史支付单会先进入 {@code closing} 等平台关单。请求体只放 {@code reason}（可选，≤200 字）——
   * 字段白名单刻意只留这一个，买家不能借取消请求顺手改状态或金额。
   *
   * <p><b>状态码 202</b> 而非 200：关单/退款是异步的，响应带 {@code nextPollAfter}
   * 告诉前端几秒后轮询。{@code idempotent=true}：取消会释放"一宠一锁"，
   * 重放必须返回首次结果，不能第二次再动库存。
   */
  @PostMapping("/orders/{orderId}/cancel")
  public ResponseEntity<Object> cancel(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.cancel(orderId, body, context.actor()));
  }

  /**
   * POST /orders/{orderId}/payments；访问级别：BUYER。
   *
   * <p>发起线上支付——<b>已停用</b>，任何请求都会得到 409 {@code ONLINE_PAYMENT_DISABLED}，
   * 提示改为到店预约付款。
   *
   * <p>保留端点而不是删除：旧前端/旧链接仍会调用，若直接 404 会让客户端以为接口地址写错。
   * 另外 service 会先做 {@code orders.owned} 再抛 409，保持"非本人 404"的一致语义，
   * 使调用方无法用错误码差异探测订单是否存在。
   *
   * <p>仍然声明 {@code idempotent=true}：这是资金入口，恢复支付后必须带幂等保护；
   * 现阶段请求依然要提供 {@code Idempotency-Key}，只是业务在写入幂等记录之前就抛出 409 了。
   */
  @PostMapping("/orders/{orderId}/payments")
  public ResponseEntity<Object> beginPayment(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.beginPayment(orderId, body, context.actor()));
  }

  /**
   * POST /orders/{orderId}/pickup-verification-codes；访问级别：BUYER。
   *
   * <p>发送自提确认短信：发给订单联系号码，验证码 {@code scope} 绑定订单 id，只能用于本单确认。
   * 发送前先过 {@code pickupAllowed}（订单已付、未过自提保留期、无进行中的售后）——
   * 不该提货的单不该再消耗一条短信，也不该给买家"还能提"的错觉。
   *
   * <p>{@code idempotent=false}：接口有真实外部副作用，缓存响应会让用户重复点"发送"却收不到码。
   * 防刷依赖 service 里的 IP 限流，因此必须传 {@code context.ip()}。
   *
   * <p>成功 200，返回 {@code smsRequestId}，后续现场确认需原样回传。
   */
  @PostMapping("/orders/{orderId}/pickup-verification-codes")
  public ResponseEntity<Object> pickupCode(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        false,
        context -> service.pickupCode(orderId, body, context.actor(), context.ip()));
  }

  /**
   * POST /orders/{orderId}/pickup-confirmations；访问级别：BUYER。
   *
   * <p>买家现场确认（交付流程的第一半）：请求体带 {@code smsRequestId}/{@code smsCode}、
   * 确认文本版本与哈希、{@code accepted=true}，以及 {@code checks} 里四项必为 true 的检查
   * （精神状态、眼鼻、毛况、排泄）。四项是强校验不是提示——活体交易最容易起纠纷的就是这里。
   *
   * <p>成功 200，返回 {@code confirmationId}、{@code validUntil}（10 分钟且不晚于自提截止时刻）
   * 与健康保障到期时间；店长/店员核销时必须回传同一个 {@code confirmationId}。
   * 写操作，{@code idempotent=true}，需带 {@code Idempotency-Key}，
   * 避免重复提交产生多条确认记录。
   */
  @PostMapping("/orders/{orderId}/pickup-confirmations")
  public ResponseEntity<Object> confirmPickup(
      @PathVariable String orderId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        true,
        context -> service.confirmPickup(orderId, body, context.actor()));
  }
}
