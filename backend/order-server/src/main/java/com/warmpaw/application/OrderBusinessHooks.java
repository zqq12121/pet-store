package com.warmpaw.application;
import static com.warmpaw.common.Json.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 交易域专属的扩展钩子，只部署在 order-server。
 *
 * <p><b>为什么需要钩子</b>：{@code BusinessOperationExecutor}（公共模块）负责统一的事务、
 * 鉴权、幂等与审计，但它不能依赖订单/支付这些具体领域。于是定义 {@link BusinessHooks} 接口，
 * 由各服务提供实现：order-server 需要"事务提交后调支付平台"，admin-server 则不需要。
 *
 * <p><b>为什么不能放在 admin-server</b>：支付平台调用一旦被两个服务同时执行，
 * 同一笔单会被下单两次。钩子只部署在交易服务，从部署结构上消除了这种可能。
 */
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

  /**
   * 事务提交后的收尾：只有"创建支付单"这一种操作需要真的去调微信。
   *
   * <p>判据是 {@code kind=payment && method=POST}——即刚刚生成了一条支付意图。
   * 此时事务已经提交，{@code PaymentWorker} 可以放心地访问外部网络，不会把 HTTP 等待
   * 拖进库存锁里。
   *
   * <p>返回的是 {@code payments.view(...)} 而不是 worker 的结果：工作器只负责推进状态，
   * 对外响应要用统一的支付单视图（含 {@code invokeParams}/{@code h5Url}）。
   * 其它操作原样返回，不影响正常响应。
   */
  @Override
  public OperationResult afterCommit(OperationResult result, RequestContext context) {
    if ("payment".equals(result.kind()) && "POST".equals(context.method())) {
      worker.processPayment(result.resource(), context.ip());
      return new OperationResult(result.status(), payments.view(store.get("payment", result.resource())));
    }
    return result;
  }

  /**
   * 幂等回放：同一个 Idempotency-Key 第二次请求时，重建"此刻"的响应。
   *
   * <p>关键点是<b>重新查实体再拼视图</b>，而不是把首次的响应体存起来直接返回。
   * 因为首次请求之后状态可能已经推进（比如支付已成功、订单已交付），
   * 直接回放旧响应会让客户端一直看到过期状态。
   *
   * <p>按 {@code kind} 分派各自的视图：
   *
   * <ul>
   *   <li>{@code order} —— 取消走 202 的专用结构，交付原样返回首次结果（交付是终态，不会变），
   *       其余走详情视图；
   *   <li>{@code payment} —— 支付单视图；
   *   <li>{@code after_sale} —— 换宠操作返回 {@code exchange} 子对象，其余返回售后详情；
   *   <li>{@code refund} —— 202 + 退款 DTO。
   * </ul>
   *
   * <p>缺少 {@code kind}/{@code resource} 时回落到保存的原始数据，保证老记录也能正确回放。
   *
   * @param saved  首次执行时持久化的幂等记录（含 kind、resource、data）
   * @param path   本次请求路径，用于区分同一资源的不同动作
   * @param admin  是否后台视角，决定订单详情的字段范围
   */
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

  /** 取消结果视图：仍在关单/退款中时提示客户端 2 秒后再查。 */
  private Map<String, Object> cancelResult(Map<String, Object> o) {
    return map(
        "orderId",
        o.get("id"),
        "status",
        o.get("status"),
        "nextPollAfter",
        List.of("closing", "refunding").contains(text(o, "status")) ? 2 : 0);
  }

  /** 退款单对外投影，刻意不含 {@code attempted}/{@code providerAccepted} 等内部重试标志。 */
  private Map<String, Object> refundDto(Map<String, Object> r) {
    return CatalogReader.select(
        r, "id,refundNo,afterSaleId,reason,amount,status,createdAt,succeededAt,failureReason");
  }
}
