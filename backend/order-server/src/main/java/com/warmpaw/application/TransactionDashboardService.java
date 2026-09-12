package com.warmpaw.application;
import static com.warmpaw.common.Json.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
/** 订单服务汇总交易统计，后台通过服务调用获取。 */
@Service
public class TransactionDashboardService {
  private final BusinessRepository store;
  public TransactionDashboardService(BusinessRepository store) { this.store = store; }
  public Map<String, Object> dashboard() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    long
        gross =
            store.list("payment_fact").stream()
                .filter(p -> day(p, "paidAt", today))
                .mapToLong(p -> number(p, "amount"))
                .sum(),
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
        store.list("payment_fact").stream().filter(p -> day(p, "paidAt", today)).count(),
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
            "pickupCount",
            store.list("order").stream().filter(o -> "paid".equals(text(o, "status"))).count(),
            "afterSaleReviewCount",
            store.list("after_sale").stream()
                .filter(s -> "pending_review".equals(text(s, "status")))
                .count(),
            "failedRefundCount",
            store.list("refund").stream().filter(r -> "failed".equals(text(r, "status"))).count(),
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
