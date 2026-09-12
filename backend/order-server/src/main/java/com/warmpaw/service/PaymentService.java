package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 先持久化意图，再由提交后的工作器访问平台；失败与未知结果均不释放库存。 */
@Service
public class PaymentService {
  private final BusinessRepository store;
  private final OrderService orders;
  private final AuthService auth;
  private final WechatGateway gateway;

  public PaymentService(
      BusinessRepository store, OrderService orders, AuthService auth, WechatGateway gateway) {
    this.store = store;
    this.orders = orders;
    this.auth = auth;
    this.gateway = gateway;
  }

  public Map<String, Object> capabilities() {
    return map(
        "currency",
        "CNY",
        "methods",
        List.of("jsapi", "h5").stream()
            .map(
                s ->
                    map(
                        "scene",
                        s,
                        "enabled",
                        false,
                        "disabledReason",
                        "线上支付已停用，请预约到店付款"))
            .toList());
  }

  public Map<String, Object> payment(Map<String, Object> o) {
    String id = text(object(o, "payment"), "paymentId");
    return id == null ? null : store.get("payment", id);
  }

  public Map<String, Object> begin(Map<String, Object> o, Map<String, Object> body) {
    Input in = new Input(body, "scene,returnPath");
    String scene = in.choice("scene", "jsapi,h5");
    if (in.has("returnPath")) com.warmpaw.common.ProviderSupport.safePath(in.str("returnPath", 1, 500));
    BusinessRepository.state(o, "pending_paid");
    require(
        !Instant.now().isAfter(Instant.parse(text(o, "expiresAt"))), 409, "ORDER_EXPIRED", "付款已超期");
    require(auth.local() || gateway.enabled(scene), 422, "PAYMENT_SCENE_UNAVAILABLE", "支付场景未开通");
    Map<String, Object> p = payment(o);
    if (p != null && !"closed".equals(text(p, "status"))) {
      require(scene.equals(text(p, "scene")), 409, "PAYMENT_IN_PROGRESS", "请等待旧支付单关闭后再切换场景");
      return p;
    }
    if (!auth.local() && scene.equals("jsapi"))
      require(
          store.byKey("wechat", gateway.appId() + ":" + o.get("ownerId")) != null,
          409,
          "WECHAT_AUTH_REQUIRED",
          "请先完成微信授权");
    String out = UUID.randomUUID().toString().replace("-", "");
    p =
        store.create(
            "payment",
            text(o, "ownerId"),
            out,
            map(
                "outTradeNo",
                out,
                "orderId",
                o.get("id"),
                "scene",
                scene,
                "status",
                "pending",
                "expiresAt",
                o.get("expiresAt"),
                "providerCreated",
                false,
                "providerAttempted",
                false,
                "lastQueryAt",
                null));
    o.put(
        "payment",
        map("paymentId", p.get("id"), "status", "pending", "paidAmount", 0, "paidAt", null));
    store.save(o);
    return p;
  }

  public Map<String, Object> view(Map<String, Object> p) {
    Map<String, Object> r =
        map(
            "paymentId",
            p.get("id"),
            "status",
            p.get("status"),
            "scene",
            p.get("scene"),
            "expiresAt",
            p.get("expiresAt"));
    if ("pending".equals(text(p, "status"))
        && Instant.parse(text(p, "expiresAt")).isAfter(Instant.now())) {
      if (p.containsKey("invokeParams")) r.put("invokeParams", p.get("invokeParams"));
      if (p.containsKey("h5Url")) r.put("h5Url", p.get("h5Url"));
    }
    return r;
  }

  public Map<String, Object> status(Map<String, Object> o) {
    Map<String, Object> p = object(o, "payment");
    return map(
        "orderId",
        o.get("id"),
        "paymentId",
        p.get("paymentId"),
        "status",
        p.get("status"),
        "paidAmount",
        p.get("paidAmount"),
        "paidAt",
        p.get("paidAt"),
        "orderStatus",
        o.get("status"),
        "nextPollAfter",
        List.of("pending_paid", "closing").contains(text(o, "status")) ? 2 : 0);
  }

  public void cancel(Map<String, Object> o, String reason) {
    BusinessRepository.state(o, "pending_paid");
    o.put("status", "closing");
    o.put("cancelReason", reason);
    store.save(o);
    if (payment(o) == null) closed(o);
  }

  public void closed(Map<String, Object> o) {
    Map<String, Object> p = payment(o);
    if (p != null && "succeeded".equals(text(p, "status"))) return;
    require(
        p == null || "closed".equals(text(p, "status")), 409, "PAYMENT_IN_PROGRESS", "尚未确认平台关单");
    o.put("status", "cancelled");
    o.put("closedAt", Instant.now().toString());
    o.put("cancelledAt", Instant.now().toString());
    if (p != null) object(o, "payment").put("status", "closed");
    orders.release(o);
    store.save(o);
  }

  public void paid(Map<String, Object> p, Map<String, Object> fact) {
    Map<String, Object> o = store.get("order", text(p, "orderId"));
    Map<String, Object> amount = object(fact, "amount");
    require(
        "SUCCESS".equals(text(fact, "trade_state"))
            && text(p, "outTradeNo").equals(text(fact, "out_trade_no"))
            && number(amount, "total") == number(o, "amount")
            && "CNY".equals(text(amount, "currency"))
            && (!amount.containsKey("payer_total")
                || number(amount, "payer_total") == number(o, "amount")),
        400,
        "INVALID_ARGUMENT",
        "支付金额或状态不匹配");
    String tx = text(fact, "transaction_id");
    require(tx != null && !tx.isBlank(), 400, "INVALID_ARGUMENT", "支付流水缺失");
    Map<String, Object> existing = store.byKey("payment_fact", tx);
    if (existing != null) {
      require(p.get("id").equals(existing.get("paymentId")), 409, "ORDER_STATE_CONFLICT", "支付流水冲突");
      return;
    }
    require(!"succeeded".equals(text(p, "status")), 409, "ORDER_STATE_CONFLICT", "支付单已有不同成功流水");
    Instant paid = Instant.parse(text(fact, "success_time"));
    require(!paid.isAfter(Instant.now().plusSeconds(60)), 400, "INVALID_ARGUMENT", "支付时间无效");
    store.create(
        "payment_fact",
        text(o, "ownerId"),
        tx,
        map(
            "paymentId",
            p.get("id"),
            "orderId",
            o.get("id"),
            "paidAt",
            paid.toString(),
            "amount",
            o.get("amount")));
    p.put("status", "succeeded");
    p.put("transactionId", tx);
    store.save(p);
    String previous = text(o, "status");
    o.put("paidAt", paid.toString());
    o.put(
        "pickupDeadlineAt", paid.plusSeconds(number(o, "pickupRetentionHours") * 3600).toString());
    o.put(
        "payment",
        map(
            "paymentId",
            p.get("id"),
            "status",
            "succeeded",
            "paidAmount",
            o.get("amount"),
            "paidAt",
            paid.toString()));
    o.put("status", "paid");
    boolean conflict =
        !List.of("pending_paid", "closing").contains(previous)
            || o.get("cancelReason") != null
            || paid.isAfter(Instant.parse(text(o, "expiresAt")))
            || !text(o, "id").equals(store.occupation(text(o, "petId")));
    if (conflict) {
      store.save(o);
      refund(o, null, number(o, "amount"), "payment_conflict");
    } else {
      String code;
      do {
        code = auth.digits(8);
      } while (codeExists(code));
      o.put(
          "pickup",
          map(
              "code",
              code,
              "qrPayload",
              auth.randomToken(),
              "expiresAt",
              o.get("pickupDeadlineAt"),
              "buyerConfirmedAt",
              null));
      store.save(o);
    }
  }

  private boolean codeExists(String code) {
    return store.list("order").stream()
        .anyMatch(
            o ->
                "paid".equals(text(o, "status")) && code.equals(text(object(o, "pickup"), "code")));
  }

  public Map<String, Object> refund(
      Map<String, Object> o, Map<String, Object> sale, long amount, String reason) {
    long committed =
        store.list("refund").stream()
            .filter(r -> Objects.equals(o.get("id"), r.get("orderId")))
            .mapToLong(r -> number(r, "amount"))
            .sum();
    require(
        amount > 0 && committed + amount <= number(object(o, "payment"), "paidAmount"),
        422,
        "REFUND_AMOUNT_EXCEEDED",
        "退款总额超出可退金额");
    String no = UUID.randomUUID().toString().replace("-", "");
    Map<String, Object> r =
        store.create(
            "refund",
            text(o, "ownerId"),
            no,
            map(
                "refundNo",
                no,
                "orderId",
                o.get("id"),
                "afterSaleId",
                sale == null ? null : sale.get("id"),
                "reason",
                reason,
                "amount",
                amount,
                "status",
                "processing",
                "succeededAt",
                null,
                "failureReason",
                null,
                "attempted",
                false,
                "providerAccepted",
                false));
    if (!"refunding".equals(text(o, "status"))) o.put("refundPreviousStatus", o.get("status"));
    o.put("status", "refunding");
    o.put("pickup", null);
    store.save(o);
    if (sale != null) {
      sale.put("status", "refunding");
      sale.put("refundId", r.get("id"));
      store.save(sale);
    }
    return r;
  }

  public void refunded(Map<String, Object> r, Map<String, Object> fact) {
    Map<String, Object> o = store.get("order", text(r, "orderId")), p = payment(o);
    Map<String, Object> amount = object(fact, "amount");
    require(
        text(r, "refundNo").equals(text(fact, "out_refund_no"))
            && text(p, "outTradeNo").equals(text(fact, "out_trade_no"))
            && number(amount, "refund") == number(r, "amount")
            && number(amount, "total") == number(o, "amount")
            && "CNY".equals(text(amount, "currency")),
        400,
        "INVALID_ARGUMENT",
        "退款信息不匹配");
    String status = Objects.toString(fact.get("refund_status"), text(fact, "status"));
    if ("succeeded".equals(text(r, "status"))) return;
    if (!"SUCCESS".equals(status)) {
      if (List.of("CLOSED", "ABNORMAL").contains(status)) {
        r.put("status", "failed");
        r.put("failureReason", "平台退款异常，请核实后重试");
        store.save(r);
      }
      return;
    }
    String tx = text(fact, "refund_id");
    require(tx != null && !tx.isBlank(), 400, "INVALID_ARGUMENT", "退款流水缺失");
    Map<String, Object> old = store.byKey("refund_fact", tx);
    require(
        old == null || r.get("id").equals(old.get("refundId")),
        409,
        "ORDER_STATE_CONFLICT",
        "退款流水冲突");
    if (old == null)
      store.create(
          "refund_fact",
          text(o, "ownerId"),
          tx,
          map(
              "refundId",
              r.get("id"),
              "amount",
              r.get("amount"),
              "succeededAt",
              fact.get("success_time")));
    r.put("status", "succeeded");
    r.put("succeededAt", Objects.toString(fact.get("success_time"), Instant.now().toString()));
    r.put("failureReason", null);
    store.save(r);
    long sum =
        store.list("refund").stream()
            .filter(
                f ->
                    Objects.equals(f.get("orderId"), o.get("id"))
                        && "succeeded".equals(text(f, "status")))
            .mapToLong(f -> number(f, "amount"))
            .sum();
    require(sum <= number(o, "amount"), 422, "REFUND_AMOUNT_EXCEEDED", "退款金额异常");
    o.put("refundedAmount", sum);
    o.put("status", sum == number(o, "amount") ? "refunded" : o.get("refundPreviousStatus"));
    if (sum == number(o, "amount") && o.get("pickedUpAt") == null) orders.release(o);
    store.save(o);
    if (r.get("afterSaleId") != null) {
      Map<String, Object> s = store.get("after_sale", text(r, "afterSaleId"));
      s.put("status", sum == number(o, "amount") ? "refunded" : "resolved");
      store.save(s);
    }
  }

  public Map<String, Object> mockFact(Map<String, Object> p, Map<String, Object> o) {
    return map(
        "out_trade_no",
        p.get("outTradeNo"),
        "trade_state",
        "SUCCESS",
        "transaction_id",
        "local_" + p.get("id"),
        "success_time",
        Instant.now().toString(),
        "amount",
        map("total", o.get("amount"), "payer_total", o.get("amount"), "currency", "CNY"));
  }
}
