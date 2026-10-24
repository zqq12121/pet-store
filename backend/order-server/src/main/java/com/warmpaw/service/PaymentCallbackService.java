package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 微信支付回调入口的业务处理。
 *
 * <p>处理顺序不可调换：
 *
 * <ol>
 *   <li>先验签（{@link WechatGateway#verify}）——这一步只用原始报文，不解析 JSON，
 *       因为签名覆盖的是字节内容，先解析再验签会丢失原始格式；
 *   <li>再解密 {@code resource}（AES-256-GCM），得到真正的交易事实；
 *   <li>最后在<b>同一个事务</b>里加锁、校验商户与金额、幂等入账。
 * </ol>
 *
 * <p>为什么校验放在事务里而不是之前：金额要跟订单当前值比对，订单可能在验签期间被改；
 * 拿全局锁后再读，才能保证"看到的金额"和"写入的状态"基于同一份数据。
 *
 * <p>幂等由下两层共同保证：这里按 {@code out_trade_no}/{@code out_refund_no} 找到本地单，
 * 而 {@link PaymentService#paid}/{@link PaymentService#refunded} 内部还会用平台流水号去重。
 * 微信会重复投递同一通知，直到收到成功响应为止。
 */
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

  /**
   * 处理一条回调通知。
   *
   * <p>用 {@code kind} 区分支付与退款两条通道，其余参数是微信要求的四个签名头加原始报文。
   *
   * <p>支付通知额外校验 {@code event_type == TRANSACTION.SUCCESS}——非成功事件（如关单）不走入账逻辑；
   * 退款通知接受 SUCCESS / ABNORMAL / CLOSED 三种，因为异常与关闭也需要落状态。
   *
   * <p>找不到本地单时返回 400 而不是忽略：说明收到了一条对不上账的通知，
   * 这类异常必须暴露出来，不能静默吞掉。
   */
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
