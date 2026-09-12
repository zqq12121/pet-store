package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 外部调用位于数据库提交之后；稳定支付/退款单号使进程重启后仍可查单恢复。 */
@Component
public class PaymentWorker {
  private final BusinessRepository store;
  private final PaymentService payments;
  private final WechatGateway gateway;
  private final AuthService auth;
  private final TransactionTemplate tx;

  public PaymentWorker(
      BusinessRepository store,
      PaymentService payments,
      WechatGateway gateway,
      AuthService auth,
      PlatformTransactionManager manager) {
    this.store = store;
    this.payments = payments;
    this.gateway = gateway;
    this.auth = auth;
    this.tx = new TransactionTemplate(manager);
  }

  private <T> T locked(Supplier<T> callback) {
    return tx.execute(
        s -> {
          store.lock();
          return callback.get();
        });
  }

  public synchronized void processPayment(String id, String ip) {
    Map<String, Object> p = store.get("payment", id), o = store.get("order", text(p, "orderId"));
    if ("succeeded".equals(text(p, "status")) || "closed".equals(text(p, "status"))) return;
    boolean closing = "closing".equals(text(o, "status"));
    if (auth.local()) {
      if (closing)
        locked(
            () -> {
              Map<String, Object> current = store.get("payment", id);
              if (!"succeeded".equals(text(current, "status"))) {
                current.put("status", "closed");
                store.save(current);
                payments.closed(store.get("order", text(current, "orderId")));
              }
              return null;
            });
      return;
    }
    try {
      if (Boolean.TRUE.equals(p.get("providerAttempted"))) {
        Map<String, Object> fact = gateway.queryPayment(p);
        String state = text(fact, "trade_state");
        if ("SUCCESS".equals(state)) {
          gateway.checkMerchant(fact);
          locked(
              () -> {
                payments.paid(store.get("payment", id), fact);
                return null;
              });
          return;
        }
        if ("CLOSED".equals(state)) {
          locked(
              () -> {
                Map<String, Object> c = store.get("payment", id);
                c.put("status", "closed");
                store.save(c);
                Map<String, Object> order = store.get("order", text(c, "orderId"));
                if ("closing".equals(text(order, "status"))) payments.closed(order);
                return null;
              });
          return;
        }
      }
      if (closing) {
        if (Boolean.TRUE.equals(p.get("providerAttempted"))) gateway.close(p);
        locked(
            () -> {
              Map<String, Object> c = store.get("payment", id);
              if (!"succeeded".equals(text(c, "status"))) {
                c.put("status", "closed");
                store.save(c);
                payments.closed(store.get("order", text(c, "orderId")));
              }
              return null;
            });
        return;
      }
      if (!Boolean.TRUE.equals(p.get("providerCreated"))) {
        boolean allowed =
            locked(
                () -> {
                  Map<String, Object> c = store.get("payment", id),
                      order = store.get("order", text(c, "orderId"));
                  if (!"pending_paid".equals(text(order, "status"))) return false;
                  c.put("providerAttempted", true);
                  store.save(c);
                  return true;
                });
        if (!allowed) return;
        Map<String, Object> identity =
            store.byKey("wechat", gateway.appId() + ":" + o.get("ownerId"));
        Map<String, Object> result =
            gateway.prepay(o, p, ip, identity == null ? null : text(identity, "openid"));
        locked(
            () -> {
              Map<String, Object> c = store.get("payment", id);
              if ("pending".equals(text(c, "status"))) {
                c.putAll(result);
                c.put("providerCreated", true);
                store.save(c);
              }
              return null;
            });
      }
    } catch (ApiException e) {
      locked(
          () -> {
            Map<String, Object> c = store.get("payment", id);
            c.put("lastProviderError", e.code);
            c.put("lastQueryAt", Instant.now().toString());
            store.save(c);
            return null;
          });
    }
  }

  public synchronized void processRefund(String id) {
    Map<String, Object> r = store.get("refund", id);
    if (!"processing".equals(text(r, "status"))) return;
    Map<String, Object> o = store.get("order", text(r, "orderId")), p = payments.payment(o);
    try {
      Map<String, Object> fact;
      if (auth.local())
        fact =
            map(
                "out_refund_no",
                r.get("refundNo"),
                "out_trade_no",
                p.get("outTradeNo"),
                "refund_id",
                "local_" + r.get("id"),
                "status",
                "SUCCESS",
                "success_time",
                Instant.now().toString(),
                "amount",
                map("refund", r.get("amount"), "total", o.get("amount"), "currency", "CNY"));
      else if (Boolean.TRUE.equals(r.get("attempted"))) fact = gateway.queryRefund(r);
      else {
        locked(
            () -> {
              Map<String, Object> c = store.get("refund", id);
              c.put("attempted", true);
              store.save(c);
              return null;
            });
        fact = gateway.refund(r, p, o);
      }
      locked(
          () -> {
            Map<String, Object> c = store.get("refund", id);
            c.put("providerAccepted", true);
            payments.refunded(c, fact);
            store.save(c);
            return null;
          });
    } catch (ApiException e) {
      locked(
          () -> {
            Map<String, Object> c = store.get("refund", id);
            c.put("failureReason", "平台结果暂不明确，等待对账");
            store.save(c);
            return null;
          });
    }
  }

  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void reconcile() {
    locked(
        () -> {
          for (Map<String, Object> o : store.list("order")) {
            if ("pending_paid".equals(text(o, "status"))
                && Instant.now().isAfter(Instant.parse(text(o, "expiresAt"))))
              payments.cancel(o, "payment_timeout");
            else if ("paid".equals(text(o, "status"))
                && Instant.now().isAfter(Instant.parse(text(o, "pickupDeadlineAt")))) {
              Map<String, Object> s =
                  store.list("after_sale").stream()
                      .filter(
                          a ->
                              o.get("id").equals(a.get("orderId"))
                                  && "pending_review".equals(text(a, "status")))
                      .findFirst()
                      .orElse(null);
              if (s != null) {
                s.put("approvedResolution", "full_refund");
                s.put("reviewReason", "自提超期自动退款");
              }
              payments.refund(
                  o, s, number(o, "amount") - number(o, "refundedAmount"), "pickup_timeout");
            }
          }
          return null;
        });
    for (Map<String, Object> p : store.list("payment"))
      if ("pending".equals(text(p, "status"))) processPayment(text(p, "id"), "127.0.0.1");
    for (Map<String, Object> r : store.list("refund"))
      if ("processing".equals(text(r, "status"))) processRefund(text(r, "id"));
  }
}
