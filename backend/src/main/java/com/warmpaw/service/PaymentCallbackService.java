package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 微信通知先验签解密，再在同一事务中校验商户及金额、幂等入账。 */
@Service
public class PaymentCallbackService {
  private final BusinessRepository store;
  private final PaymentService payments;
  private final WechatGateway gateway;
  private final TransactionTemplate tx;

  public PaymentCallbackService(
      BusinessRepository store,
      PaymentService payments,
      WechatGateway gateway,
      PlatformTransactionManager manager) {
    this.store = store;
    this.payments = payments;
    this.gateway = gateway;
    this.tx = new TransactionTemplate(manager);
  }

  public void handle(
      String kind, String raw, String timestamp, String nonce, String serial, String signature) {
    require(List.of("payments", "refunds").contains(kind), 404, "RESOURCE_NOT_FOUND", "通知无效");
    gateway.verify(timestamp, nonce, serial, signature, raw);
    Map<String, Object> notification = read(raw), fact = gateway.decrypt(notification);
    tx.execute(
        s -> {
          store.lock();
          if (kind.equals("payments")) {
            require(
                "TRANSACTION.SUCCESS".equals(text(notification, "event_type")),
                400,
                "INVALID_ARGUMENT",
                "通知类型无效");
            gateway.checkMerchant(fact);
            Map<String, Object> p = store.byKey("payment", text(fact, "out_trade_no"));
            require(p != null, 400, "INVALID_ARGUMENT", "订单不存在");
            payments.paid(p, fact);
          } else {
            require(
                List.of("REFUND.SUCCESS", "REFUND.ABNORMAL", "REFUND.CLOSED")
                        .contains(text(notification, "event_type"))
                    && gateway.merchant().equals(text(fact, "mchid")),
                400,
                "INVALID_ARGUMENT",
                "退款通知无效");
            Map<String, Object> r = store.byKey("refund", text(fact, "out_refund_no"));
            require(r != null, 400, "INVALID_ARGUMENT", "退款不存在");
            payments.refunded(r, fact);
          }
          return null;
        });
  }
}
