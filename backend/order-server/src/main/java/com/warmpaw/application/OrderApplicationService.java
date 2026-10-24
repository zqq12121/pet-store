package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;
import static com.warmpaw.common.TimeRange.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogReader;
import com.warmpaw.service.OrderService;
import com.warmpaw.service.PaymentService;
import com.warmpaw.service.TemporaryStore;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 订单用例编排层：把 Controller 的一次请求翻译成"鉴权 → 事务 → 领域调用 → 统一结果"。
 *
 * <p><b>为什么单独一层</b>：{@link OrderService} 只懂业务规则，不知道 HTTP 状态码、幂等键、
 * 响应包装；Controller 只懂路由，不该知道事务边界。这一层负责把两边接起来，
 * 并返回 {@link OperationResult}（含状态码、响应体、资源类型与 id）。
 *
 * <p><b>事务由外层开启</b>：类上标注 {@code Propagation.MANDATORY}，意味着调用方必须已经在事务里。
 * 实际的事务与全局锁由 {@code BusinessOperationExecutor} 统一管理——
 * 这样"鉴权失败要不要写审计""幂等回放要不要开事务"这类问题只有一处答案。
 *
 * <p><b>{@code OperationResult} 的 {@code kind}/{@code resource} 是幂等回放的依据</b>：
 * 同一个 Idempotency-Key 重放时，{@code OrderBusinessHooks.replay} 会用它们去查最新实体，
 * 重新拼一个与首次一致的响应，而不是把首次的响应体原样吐回去（状态可能已推进）。
 *
 * <p><b>当前业务</b>：{@link #create} 走的是"到店预约"（委托 {@code AppointmentService}），
 * 线上支付入口 {@link #beginPayment} 已固定返回 409。
 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class OrderApplicationService {
  private final BusinessRepository store;
  private final OrderService orders;
  private final PaymentService payments;
  private final AuthService auth;
  private final TemporaryStore temp;
  private final com.warmpaw.service.AppointmentService appointments;

  public OrderApplicationService(
      BusinessRepository store,
      OrderService orders,
      PaymentService payments,
      AuthService auth,
      TemporaryStore temp, com.warmpaw.service.AppointmentService appointments) {
    this.appointments = appointments;
    this.store = store;
    this.orders = orders;
    this.payments = payments;
    this.auth = auth;
    this.temp = temp;
  }

  /** 结算预览：不下单、不加锁，纯读。 */
  public OperationResult preview(Map<String, Object> body) {
    return new OperationResult(200, orders.preview(body));
  }

  /**
   * 创建订单——实际创建的是"到店预约"。
   *
   * <p>返回 201 且响应体用 {@code detail(order, false)} 而不是领域对象原样返回：
   * 领域对象里有 {@code ownerId} 等内部字段，买家视图必须裁剪。
   */
  public OperationResult create(Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> order = appointments.create(body, actor);
    return new OperationResult(201, orders.detail(order, false), "order", text(order, "id"));
  }

  /** 买家订单列表：只返回自己的单，视图为摘要。 */
  public OperationResult list(Map<String, Object> query, AuthService.Actor actor) {
    return new OperationResult(200, listOrders(query, actor, false));
  }

  /** 后台订单列表：全量可见，额外带联系人与备注。 */
  public OperationResult adminList(Map<String, Object> query, AuthService.Actor actor) {
    return new OperationResult(200, listOrders(query, actor, true));
  }

  /** 买家订单详情：先校验归属，再按买家视图返回。 */
  public OperationResult detail(String orderId, AuthService.Actor actor) {
    return new OperationResult(200, orders.detail(orders.owned(orderId, actor), false));
  }

  /**
   * 后台订单详情。
   *
   * <p>这里<b>不做归属校验</b>（员工可看全部单），但必须写审计——
   * 查看他人订单包含手机号等个人信息，属于敏感读操作。
   */
  public OperationResult adminDetail(String orderId, AuthService.Actor actor) {
    store.audit(actor.id(), "order.read", orderId);
    return new OperationResult(200, orders.detail(store.get("order", orderId), true));
  }

  /**
   * 取消订单：按是否存在 {@code appointment} 分流到预约取消或支付关单。
   *
   * <p>返回 202 而非 200：支付路径下订单会先进入 {@code closing}，
   * 还要等平台确认关单才算真正取消，因此用"已接受、处理中"的语义。
   *
   * <p>请求体允许空 {@code reason}，但字段白名单只放 {@code reason}——
   * 买家不能借取消请求顺手改状态或金额。
   */
  public OperationResult cancel(String orderId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> order = orders.owned(orderId, actor);
    new Input(body, "reason").optional("reason", 200);
    if (order.containsKey("appointment")) appointments.cancel(order, "user_cancelled", actor.id());
    else payments.cancel(order, "user_cancelled");
    return new OperationResult(202, cancelResult(order), "order", orderId);
  }

  /** 支付状态轮询（历史订单仍可查）。 */
  public OperationResult paymentStatus(String orderId, AuthService.Actor actor) {
    return new OperationResult(200, payments.status(orders.owned(orderId, actor)));
  }

  /**
   * 发起线上支付——<b>已停用</b>。
   *
   * <p>仍然先调 {@code orders.owned} 再抛 409：既保持"无权限返回 404"的一致语义，
   * 也让调用方无法通过错误码差异探测订单是否存在。
   */
  public OperationResult beginPayment(
      String orderId, Map<String, Object> body, AuthService.Actor actor) {
    orders.owned(orderId, actor);
    throw new ApiException(409, "ONLINE_PAYMENT_DISABLED", "已改为预约到店付款，请联系门店处理旧订单");
  }

  /**
   * 店长处理预约：确认 / 取消 / 完成交付。
   *
   * <p>这里先 {@code store.get} 取单再交给领域层判断权限与状态——
   * 权限判定（员工只能 complete）在 {@code AppointmentService.process} 内部，
   * 不在编排层重复写一遍，避免两处规则漂移。
   *
   * <p>该操作仍经过请求执行器的鉴权、幂等校验与库存事务锁，三层保护没有因为多了编排层而减少。
   */
  public OperationResult appointmentAction(String orderId, String action, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> order = store.get("order", orderId);
    appointments.process(order, action, body, actor);
    return new OperationResult(200, orders.detail(order, true), "order", orderId);
  }

  /**
   * 发送自提确认短信。
   *
   * <p>先过 {@code pickupAllowed} 再发码：已超期或有售后进行中的订单不该再消耗一条短信，
   * 也不该给买家"还能提货"的错觉。
   *
   * <p>验证码的 {@code scope} 用订单 id，确保这个码只能用于本单确认。
   */
  public OperationResult pickupCode(
      String orderId, Map<String, Object> body, AuthService.Actor actor, String ip) {
    Map<String, Object> order = orders.owned(orderId, actor);
    new Input(body, "");
    orders.pickupAllowed(order);
    return new OperationResult(
        200, auth.sendSms(text(order, "contactPhone"), "pickup_confirm", orderId, ip));
  }

  /** 买家提交现场确认（四项检查 + 短信验证码）。 */
  public OperationResult confirmPickup(
      String orderId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, orders.confirm(orders.owned(orderId, actor), body, actor));
  }

  /**
   * 店员扫码/输码查单，带失败限流。
   *
   * <p>自提码是 8 位纯数字，理论上可枚举。这里限制同一员工 60 秒内最多 5 次查不到，
   * 超限抛 429。成功一次则清零计数——正常使用的店员不会被误伤。
   *
   * <p>只对 404（凭证无效）计数，其它异常不计数：数据库故障不该导致员工被锁。
   */
  public OperationResult lookupPickup(Map<String, Object> body, AuthService.Actor actor) {
    temp.checkFailures("pickup-lookup:" + actor.id(), 5);
    try {
      OperationResult result = new OperationResult(200, orders.lookup(body));
      temp.resetFailures("pickup-lookup:" + actor.id());
      return result;
    } catch (ApiException e) {
      if (e.status == 404) temp.failed("pickup-lookup:" + actor.id(), 60);
      throw e;
    }
  }

  /**
   * 完成交付（店员核销）。
   *
   * <p>对 {@code staff} 角色额外收紧字段白名单：不允许传 {@code deliveryQuarantine}。
   * 原因：检疫材料是合规凭据，只能由店长上传并核验；如果店员能在交付请求里顺手替换，
   * 等于绕过了整套检疫校验。
   */
  public OperationResult pickup(String orderId, Map<String, Object> body, AuthService.Actor actor) {
    // 员工核销只能使用已核验档案，不得借交付请求替换检疫材料。
    if ("staff".equals(actor.role())) new Input(body, "confirmationId,pickupCode,qrPayload,quarantineVerified");
    return new OperationResult(
        200, orders.pickup(store.get("order", orderId), body, actor), "order", orderId);
  }

  /**
   * 本地模拟支付，仅供 {@code mock-providers=true} 环境联调。
   *
   * <p>非本地模式返回 404 而不是 403：这个接口在线上"不存在"，
   * 不该让人知道系统里有这样一个后门。
   *
   * <p>模拟走的是真实链路（{@code begin} + {@code paid(mockFact)}），
   * 所以能验证金额校验、幂等、自提码生成等逻辑，而不是跳过它们。
   *
   * <p>生产环境仍会拒绝此入口。
   */
  public OperationResult simulatePayment(
      String orderId, Map<String, Object> body, AuthService.Actor actor) {
    require(auth.local(), 404, "RESOURCE_NOT_FOUND", "接口不存在");
    new Input(body, "");
    Map<String, Object> order = orders.owned(orderId, actor);
    BusinessRepository.state(order, "pending_paid");
    require(
        !Instant.now().isAfter(Instant.parse(text(order, "expiresAt"))),
        409,
        "ORDER_EXPIRED",
        "订单已过期");
    Map<String, Object> payment = payments.begin(order, map("scene", "h5"));
    payments.paid(payment, payments.mockFact(payment, order));
    return new OperationResult(200, orders.detail(store.get("order", orderId), false));
  }

  /** 取消结果视图；仍在关单/退款中时给 2 秒轮询建议。 */
  private Map<String, Object> cancelResult(Map<String, Object> o) {
    return map(
        "orderId",
        o.get("id"),
        "status",
        o.get("status"),
        "nextPollAfter",
        List.of("closing", "refunding").contains(text(o, "status")) ? 2 : 0);
  }

  /**
   * 订单列表的公共实现。
   *
   * <p>买家与后台用<b>两套字段白名单</b>：买家只能 {@code tab/page/pageSize}，
   * 后台可以按订单号、状态、手机号、宠物、日期区间筛选。
   *
   * <p>三个细节：
   *
   * <ul>
   *   <li>{@code tab} 把多个内部状态聚合成一个用户语义（如 {@code pending_paid} 同时含
   *       {@code closing}，{@code after_sale} 是"有售后或有退款或有退款中"）；
   *   <li>按手机号搜索会写 {@code order.phone_search} 审计——这是最敏感的查询维度，
   *       必须留痕，且审计内容不含号码本身；
   *   <li>{@code visitDate} 按 {@code Asia/Shanghai} 的本地日期匹配预约到店时间，
   *       而不是 UTC 日期，否则晚上 8 点后的预约会被算到第二天。
   * </ul>
   *
   * <p>过滤全部在内存完成（{@code store.list} 取出全量后 stream）。单店数据量小，
   * 换来不必为多条件组合维护动态 SQL。
   */
  private Map<String, Object> listOrders(
      Map<String, Object> q, AuthService.Actor a, boolean admin) {
    new Input(
        q,
        admin
            ? "orderNo,status,contactPhone,petId,createdFrom,createdTo,visitDate,page,pageSize"
            : "tab,page,pageSize");
    validateRange(q, "createdFrom", "createdTo");
    LocalDate visitDate = q.containsKey("visitDate") ? CatalogReader.parseDate(text(q, "visitDate")) : null;
    String tab = Objects.toString(q.get("tab"), "all");
    require(
        List.of("all", "pending_confirmation", "reservation_confirmed", "pending_paid", "pending_pickup", "completed", "after_sale").contains(tab),
        400,
        "VALIDATION_ERROR",
        "订单标签不正确");
    if (q.containsKey("contactPhone")) {
      new Input(q, "orderNo,status,contactPhone,petId,createdFrom,createdTo,visitDate,page,pageSize")
          .phone("contactPhone");
      store.audit(a.id(), "order.phone_search", null);
    }
    return CatalogReader.page(
        store.list("order").stream()
            .filter(o -> admin || a.id().equals(text(o, "ownerId")))
            .filter(
                o ->
                    List.of("status", "contactPhone", "petId").stream()
                        .allMatch(k -> !q.containsKey(k) || Objects.equals(q.get(k), o.get(k))))
            .filter(
                o -> !q.containsKey("orderNo") || text(o, "orderNo").contains(text(q, "orderNo")))
            .filter(o -> inRange(o, "createdAt", q, "createdFrom", "createdTo"))
            .filter(o -> visitDate == null || o.containsKey("appointment")
                && Instant.parse(text(object(o, "appointment"), "visitAt"))
                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(visitDate))
            .filter(
                o ->
                    switch (tab) {
                      case "pending_confirmation", "reservation_confirmed" -> tab.equals(text(o, "status"));
                      case "pending_paid" ->
                          List.of("pending_paid", "closing").contains(text(o, "status"));
                      case "pending_pickup" -> "paid".equals(text(o, "status"));
                      case "completed" -> "completed".equals(text(o, "status"));
                      case "after_sale" ->
                          !orders.sales(o).isEmpty()
                              || number(o, "refundedAmount") > 0
                              || "refunding".equals(text(o, "status"));
                      default -> true;
                    })
            .map(o -> {
              Map<String, Object> result = orders.summary(o);
              if (admin) result.putAll(CatalogReader.select(o, "contactName,contactPhone,remark"));
              return result;
            })
            .toList(),
        q);
  }
}
