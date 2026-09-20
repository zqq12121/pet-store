package com.warmpaw.application;
import static com.warmpaw.common.Json.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AppointmentSmsOutbox;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
/** 订单服务汇总交易统计，后台通过服务调用获取。 */
@Service
public class TransactionDashboardService {
  private final BusinessRepository store;
  private final AppointmentSmsOutbox appointmentSms;
  public TransactionDashboardService(BusinessRepository store, AppointmentSmsOutbox appointmentSms) {
    this.store = store;
    this.appointmentSms = appointmentSms;
  }
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

  private boolean day(Map<String, Object> m, String key, LocalDate date) {
    return m.get(key) != null
        && Instant.parse(text(m, key))
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .equals(date);
  }
}
