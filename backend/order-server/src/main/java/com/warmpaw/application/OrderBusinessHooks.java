package com.warmpaw.application;
import static com.warmpaw.common.Json.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.util.*;
import org.springframework.stereotype.Component;
/** 交易专属扩展，只部署在 order-server，防止管理服务重复执行支付。 */
@Component
public class OrderBusinessHooks implements BusinessHooks {
  private final BusinessRepository store;
  private final OrderService orders;
  private final PaymentService payments;
  private final AfterSaleService sales;
  private final PaymentWorker worker;
  public OrderBusinessHooks(BusinessRepository store, OrderService orders, PaymentService payments,
      AfterSaleService sales, PaymentWorker worker) {
    this.store = store; this.orders = orders; this.payments = payments; this.sales = sales; this.worker = worker;
  }
  /** 原业务事务提交后才调用支付平台，不把网络操作包在库存锁内。 */
  @Override
  public OperationResult afterCommit(OperationResult result, RequestContext context) {
    if ("payment".equals(result.kind()) && "POST".equals(context.method())) {
      worker.processPayment(result.resource(), context.ip());
      return new OperationResult(result.status(), payments.view(store.get("payment", result.resource())));
    }
    return result;
  }
  public OperationResult replay(
      Map<String, Object> saved, String path, boolean admin, int status) {
    String kind = text(saved, "kind"), id = text(saved, "resource");
    if (kind == null || id == null) return new OperationResult(status, saved.get("data"));
    Map<String, Object> entity = store.get(kind, id);
    if (kind.equals("order")) {
      if (path.endsWith("/cancel")) return new OperationResult(202, cancelResult(entity));
      if (path.endsWith("/pickup")) return new OperationResult(200, saved.get("data"));
      return new OperationResult(200, orders.detail(entity, admin));
    }
    if (kind.equals("payment")) return new OperationResult(200, payments.view(entity));
    if (kind.equals("after_sale"))
      return new OperationResult(
          200, path.endsWith("/exchange") ? entity.get("exchange") : sales.detail(entity, admin));
    if (kind.equals("refund")) return new OperationResult(202, refundDto(entity));
    return new OperationResult(status, saved.get("data"));
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

  private Map<String, Object> refundDto(Map<String, Object> r) {
    return CatalogReader.select(
        r, "id,refundNo,afterSaleId,reason,amount,status,createdAt,succeededAt,failureReason");
  }
}
