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

/**
 * 支付/退款工作器：负责真正调用微信平台，以及每分钟一次的对账。
 *
 * <p><b>核心原则：外部调用永远发生在数据库提交之后</b>。{@link #locked} 把"加锁 + 改库"包在一个独立事务里，
 * 事务提交后方法才返回，之后才发起 HTTP。这样即使平台调用超时，库里也已经有"这次尝试过"的痕迹，
 * 下次可以按 {@code outTradeNo}/{@code refundNo} 去查询，而不是重新下一单。
 *
 * <p><b>两个标志位的分工</b>：
 *
 * <ul>
 *   <li>{@code providerAttempted} —— 已开始尝试调平台（可能成功、可能失败、可能未知）。
 *       为 true 后一律走"查询"而不是"重新下单"，避免向微信重复下单；
 *   <li>{@code providerCreated} —— 平台已受理并返回调起参数（JSAPI 的 {@code prepay_id} 等）。
 * </ul>
 *
 * <p><b>稳定单号</b>：{@code outTradeNo} 与 {@code refundNo} 在请求线程里就生成并持久化，
 * 所以进程重启后仍能拿着同一个号去查单恢复，不会产生孤儿支付。
 *
 * <p><b>方法级 synchronized</b>：单实例部署下防止定时任务与用户请求同时处理同一笔单。
 * 这是进程内锁，不解决多实例并发——多实例需要数据库锁或分布式锁，当前单店部署不需要。
 */
@Component
public class PaymentWorker {
  /** 调度锁的任务名；多实例必须一致，命名带服务前缀防止与其他服务撞键。 */
  private static final String TASK_RECONCILE = "order:reconcile";

  /** 锁存活 55 秒：略小于 60 秒的调度间隔，保证下一轮开始前锁已释放或自动过期。 */
  private static final Duration LOCK_TTL = Duration.ofSeconds(55);

  private final BusinessRepository store;
  private final PaymentService payments;
  private final WechatGateway gateway;
  private final AuthService auth;
  private final TransactionTemplate tx;
  private final AppointmentService appointments;
  /** 跨实例互斥：多实例部署时只让一个实例跑对账，见 {@link SchedulerLock}。 */
  private final SchedulerLock schedulerLock;

  public PaymentWorker(
      BusinessRepository store,
      PaymentService payments,
      WechatGateway gateway,
      AuthService auth,
      PlatformTransactionManager manager,
      AppointmentService appointments,
      SchedulerLock schedulerLock) {
    this.appointments = appointments;
    this.store = store;
    this.payments = payments;
    this.gateway = gateway;
    this.auth = auth;
    this.tx = new TransactionTemplate(manager);
    this.schedulerLock = schedulerLock;
  }

  /**
   * 在"全局业务锁 + 独立事务"内执行回调。
   *
   * <p>用编程式事务而不用 {@code @Transactional}，是因为这些方法里夹着 HTTP 调用：
   * 注解会把整个方法（含网络等待）包进事务，长事务会长时间占住数据库连接和业务锁。
   * 这里显式切成"短事务 + 外部调用"片段，是刻意的设计。
   */
  private <T> T locked(Supplier<T> callback) {
    return tx.execute(
        s -> {
          store.lock();
          return callback.get();
        });
  }

  /**
   * 推进一笔支付单。
   *
   * <p>决策顺序（每一步都先看库里最新状态，不信任方法开头的快照）：
   *
   * <ol>
   *   <li>已是 {@code succeeded}/{@code closed} → 直接返回，天然幂等；
   *   <li>本地模拟模式（{@code auth.local()}）→ 只在关单时把支付单置 closed，不调任何外部接口；
   *   <li>已尝试过调平台 → 先<b>查询</b>：SUCCESS 走 {@link PaymentService#paid}，
   *       CLOSED 走关单。查询不到结论（NOTPAY/USERPAYING 等）才继续往下；
   *   <li>订单处于 {@code closing} → 先通知平台关单，再在锁内确认关闭；
   *   <li>从未下单 → 在锁内确认订单仍是 {@code pending_paid} 并置 {@code providerAttempted=true}，
   *       提交后才真正调 {@code prepay}，拿到参数再回写。
   * </ol>
   *
   * <p>{@link ApiException} 被捕获后只记录错误码与查询时间，不改变支付单状态。
   * 这就是"失败与未知都不释放库存"的落点：状态留在 pending，等下一轮对账继续查。
   *
   * @param ip 下单来源 IP，透传给微信用于风控；对账场景固定为 127.0.0.1
   */
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
          // 查询结果也要校验收款商户号，防止用别人的商户单号构造回调。
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
        // 先占位再调用：提交后即使本进程崩溃，下一轮也会因为 providerAttempted 改为查询。
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
              // 只有仍为 pending 才回写参数：若等待期间已关单或已支付，不能用旧参数覆盖。
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

  /**
   * 推进一笔退款单。
   *
   * <p>与支付同构：已尝试过就查，没尝试过才发起。本地模拟模式直接构造一个 SUCCESS 事实，
   * 走与线上完全相同的 {@link PaymentService#refunded} 处理路径——这样模拟环境能真正验证状态机，
   * 而不是绕过业务逻辑。
   *
   * <p>异常时把 {@code failureReason} 写成"平台结果暂不明确，等待对账"，
   * 而不是标记失败：退款超时不等于退款失败，贸然标失败会导致人工重复退款。
   */
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
        // 同支付：先落 attempted 再调平台，保证崩溃后能查而不是重发。
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

  /**
   * 每分钟对账，三件事按顺序做：
   *
   * <ol>
   *   <li>清理过期预约（{@link AppointmentService#expire}）——放在对账里是为了只占一把锁；
   *   <li>扫描订单：{@code pending_paid} 超时 → 取消；{@code paid} 超过自提保留期 → 自动退款。
   *       超期退款时会顺带关闭"审核中"的售后单，避免售后与超期退款两条线同时动钱；
   *   <li>推动所有 {@code pending} 支付单与 {@code processing} 退款单继续向前。
   * </ol>
   *
   * <p>{@code initialDelay} 与 {@code fixedDelay} 都是 60 秒：应用启动后先留一分钟让服务稳定，
   * 之后每次任务结束再等一分钟，避免任务堆积。
   *
   * <p>前两件事在一个事务里完成（顺带取锁），第三件事逐笔单独处理，因为它们各自要调外部接口。
   */
  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void reconcile() {
    // 多实例部署时只让一个实例执行；拿不到锁说明别的实例正在跑，本轮直接跳过。
    // 单实例（或 Redis 未启用）时 tryAcquire 恒为 true，行为与改造前一致。
    if (!schedulerLock.tryAcquire(TASK_RECONCILE, LOCK_TTL)) return;
    try {
      reconcileOnce();
    } finally {
      schedulerLock.release(TASK_RECONCILE);
    }
  }

  /** 实际对账逻辑；锁的获取与释放收口在 {@link #reconcile()}，本方法可安全重入调用。 */
  private void reconcileOnce() {
    locked(
        () -> {
          appointments.expire(Instant.now());
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
