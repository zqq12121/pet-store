package com.warmpaw.application;
import static com.warmpaw.common.Json.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AppointmentSmsOutbox;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 交易看板：为后台首页汇总当日的经营指标与待办数量。
 *
 * <p><b>为什么统计口径要单独说明</b>：这个项目同时存在两种收款方式——
 * 历史的线上支付（记录在 {@code payment_fact}）和当前的线下收款（记录在订单的
 * {@code payment.status = offline_received}）。营业额必须把两者相加，否则店长会看到
 * "今天卖了 5 只，营业额 0"这种荒谬数字。
 *
 * <p><b>线上收款不伪造流水</b>：线下收款只读订单上的金额与登记时间，绝不写入 {@code payment_fact}。
 * 这一点关系到对账——{@code payment_fact} 是微信流水的镜像，混入线下数据就再也对不上账了。
 *
 * <p><b>时区</b>：所有"今天"的判断都按 {@code Asia/Shanghai} 的本地日期，而不是 UTC。
 * 否则北京时间 0:00–8:00 的订单会被算进前一天。
 */
@Service
public class TransactionDashboardService {
  private final BusinessRepository store;
  private final AppointmentSmsOutbox appointmentSms;
  public TransactionDashboardService(BusinessRepository store, AppointmentSmsOutbox appointmentSms) {
    this.store = store;
    this.appointmentSms = appointmentSms;
  }

  /**
   * 汇总当日指标。分成三块：
   *
   * <ul>
   *   <li><b>金额</b>：{@code grossSalesAmount}（线上流水 + 线下收款）、{@code refundAmount}（当日成功退款）、
   *       {@code netCashAmount}（两者相减，可为负）；
   *   <li><b>数量</b>：新建订单数、成交数、在售宠物数；
   *   <li><b>待办</b>：待确认预约、待到店、待自提、待审核售后、失败退款、待发/失败短信、支付异常单。
   * </ul>
   *
   * <p>{@code aiSessionCount} 与 {@code knowledgeFailedJobCount} 固定为 0：
   * AI 会话数据在独立服务里，不在本库。保留字段是为了让前端结构稳定，将来接上后直接填值。
   *
   * <p>{@code paymentExceptionCount} 统计带有 {@code lastProviderError} 的支付单——
   * 这类单"结果未知"，需要人工核对，是最容易被忽略的风险项。
   */
  public Map<String, Object> dashboard() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    // 线下收款按店长实际登记时间统计，不伪造微信支付流水。
    var offline = store.list("order").stream()
        .filter(o -> "offline_received".equals(text(object(o, "payment"), "status")) && day(o, "paidAt", today)).toList();
    long
        gross =
            store.list("payment_fact").stream()
                .filter(p -> day(p, "paidAt", today))
                .mapToLong(p -> number(p, "amount"))
                .sum() + offline.stream().mapToLong(o -> number(o, "amount")).sum(),
        refund =
            store.list("refund").stream()
                .filter(r -> "succeeded".equals(text(r, "status")) && day(r, "succeededAt", today))
                .mapToLong(r -> number(r, "amount"))
                .sum();
    return map(
        "date",
        today.toString(),
        "createdOrderCount",
        store.list("order").stream().filter(o -> day(o, "createdAt", today)).count(),
        "paidOrderCount",
        store.list("payment_fact").stream().filter(p -> day(p, "paidAt", today)).count() + offline.size(),
        "grossSalesAmount",
        gross,
        "refundAmount",
        refund,
        "netCashAmount",
        gross - refund,
        "onSalePetCount",
        store.list("pet").stream().filter(p -> "on_sale".equals(text(p, "status"))).count(),
        "aiSessionCount",
        0,
        "pendingTasks",
        map(
            "appointmentCount",
            store.list("order").stream().filter(o -> "pending_confirmation".equals(text(o, "status"))).count(),
            "arrivalCount",
            store.list("order").stream().filter(o -> "reservation_confirmed".equals(text(o, "status"))).count(),
            "pickupCount",
            store.list("order").stream().filter(o -> "paid".equals(text(o, "status"))).count(),
            "afterSaleReviewCount",
            store.list("after_sale").stream()
                .filter(s -> "pending_review".equals(text(s, "status")))
                .count(),
            "failedRefundCount",
            store.list("refund").stream().filter(r -> "failed".equals(text(r, "status"))).count(),
            "smsNotificationPendingCount",
            appointmentSms.pendingCount(),
            "smsNotificationFailedCount",
            appointmentSms.failedCount(),
            "knowledgeFailedJobCount",
            0,
            "paymentExceptionCount",
            store.list("payment").stream().filter(p -> p.get("lastProviderError") != null).count()),
        "updatedAt",
        Instant.now().toString());
  }

  /**
   * 判断某时间字段是否落在指定本地日期。
   *
   * <p>空值一律返回 false（而不是抛异常）：这些字段在订单生命周期里本来就可能为 null
   * （比如未支付就没有 {@code paidAt}），统计时应当安静地跳过。
   */
  private boolean day(Map<String, Object> m, String key, LocalDate date) {
    return m.get(key) != null
        && Instant.parse(text(m, key))
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .equals(date);
  }
}
