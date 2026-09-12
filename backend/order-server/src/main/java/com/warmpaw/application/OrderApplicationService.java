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

/** 订单用例：保留库存、支付和自提的事务顺序，买家操作先检查订单归属。 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class OrderApplicationService {
  private final BusinessRepository store;
  private final OrderService orders;
  private final PaymentService payments;
  private final AuthService auth;
  private final TemporaryStore temp;

  public OrderApplicationService(
      BusinessRepository store,
      OrderService orders,
      PaymentService payments,
      AuthService auth,
      TemporaryStore temp) {
    this.store = store;
    this.orders = orders;
    this.payments = payments;
    this.auth = auth;
    this.temp = temp;
  }

  public OperationResult preview(Map<String, Object> body) {
    return new OperationResult(200, orders.preview(body));
  }

  public OperationResult create(Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> order = orders.create(body, actor);
    return new OperationResult(201, orders.detail(order, false), "order", text(order, "id"));
  }

  public OperationResult list(Map<String, Object> query, AuthService.Actor actor) {
    return new OperationResult(200, listOrders(query, actor, false));
  }

  public OperationResult adminList(Map<String, Object> query, AuthService.Actor actor) {
    return new OperationResult(200, listOrders(query, actor, true));
  }

  public OperationResult detail(String orderId, AuthService.Actor actor) {
    return new OperationResult(200, orders.detail(orders.owned(orderId, actor), false));
  }

  public OperationResult adminDetail(String orderId, AuthService.Actor actor) {
    store.audit(actor.id(), "order.read", orderId);
    return new OperationResult(200, orders.detail(store.get("order", orderId), true));
  }

  public OperationResult cancel(String orderId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> order = orders.owned(orderId, actor);
    new Input(body, "reason").optional("reason", 200);
    payments.cancel(order, "user_cancelled");
    return new OperationResult(202, cancelResult(order), "order", orderId);
  }

  public OperationResult paymentStatus(String orderId, AuthService.Actor actor) {
    return new OperationResult(200, payments.status(orders.owned(orderId, actor)));
  }

  public OperationResult beginPayment(
      String orderId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> payment = payments.begin(orders.owned(orderId, actor), body);
    return new OperationResult(201, payments.view(payment), "payment", text(payment, "id"));
  }

  public OperationResult pickupCode(
      String orderId, Map<String, Object> body, AuthService.Actor actor, String ip) {
    Map<String, Object> order = orders.owned(orderId, actor);
    new Input(body, "");
    orders.pickupAllowed(order);
    return new OperationResult(
        200, auth.sendSms(text(order, "contactPhone"), "pickup_confirm", orderId, ip));
  }

  public OperationResult confirmPickup(
      String orderId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, orders.confirm(orders.owned(orderId, actor), body, actor));
  }

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

  public OperationResult pickup(String orderId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(
        200, orders.pickup(store.get("order", orderId), body, actor), "order", orderId);
  }

  /** 模拟支付仅供本地联调；生产环境仍拒绝此入口。 */
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

  private Map<String, Object> cancelResult(Map<String, Object> o) {
    return map(
        "orderId",
        o.get("id"),
        "status",
        o.get("status"),
        "nextPollAfter",
        List.of("closing", "refunding").contains(text(o, "status")) ? 2 : 0);
  }

  private Map<String, Object> listOrders(
      Map<String, Object> q, AuthService.Actor a, boolean admin) {
    new Input(
        q,
        admin
            ? "orderNo,status,contactPhone,petId,createdFrom,createdTo,page,pageSize"
            : "tab,page,pageSize");
    validateRange(q, "createdFrom", "createdTo");
    String tab = Objects.toString(q.get("tab"), "all");
    require(
        List.of("all", "pending_paid", "pending_pickup", "completed", "after_sale").contains(tab),
        400,
        "VALIDATION_ERROR",
        "订单标签不正确");
    if (q.containsKey("contactPhone")) {
      new Input(q, "orderNo,status,contactPhone,petId,createdFrom,createdTo,page,pageSize")
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
            .filter(
                o ->
                    switch (tab) {
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
            .map(orders::summary)
            .toList(),
        q);
  }
}
