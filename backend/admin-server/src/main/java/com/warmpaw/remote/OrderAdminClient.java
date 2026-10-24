package com.warmpaw.remote;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;
/**
 * 后台交易客户端：服务名经 Nacos 解析；原 Token 和幂等键显式透传。
 *
 * <p><b>方向</b>：这是 admin-server 的 Feign <b>出站</b>声明，调用 order-server 的
 * {@code /api/v1/admin/**}；入站的对端是 {@link AdminTransactionController}——
 * 两者路径一一对应、方向相反。新增后台交易接口时通常要同时改这三处
 * （本接口、转发控制器、订单服务的实际实现）。
 *
 * <p>返回值一律是原生 {@link feign.Response} 而不是业务对象：这样 4xx/5xx 不会被解码成异常，
 * 转发层能把订单服务的状态码、错误码与 {@code Retry-After} <b>原样</b>回给浏览器。
 *
 * <p>所有方法都接收 {@code headers} 映射，由调用方放入 {@code Authorization}
 * 与（可选）{@code Idempotency-Key}；本接口不自行读取上下文，
 * 便于测试时直接构造请求头。
 *
 * <p>{@code url} 默认可空，留空走服务发现；本地单机调试可用
 * {@code services.order.url} 指向固定地址。
 */
@FeignClient(name = "order-server", url = "${services.order.url:}")
public interface OrderAdminClient {
  /**
   * 预约操作透传原 Token 和幂等键。
   *
   * <p>{@code action} 为 confirm/cancel/complete；{@code complete} 允许员工，
   * 其余由订单服务要求店长。库存锁与状态机在订单服务内完成。
   */
  @PostMapping("/api/v1/admin/orders/{orderId}/appointment/{action}")
  feign.Response appointment(@PathVariable("orderId") String orderId, @PathVariable("action") String action,
      @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 后台订单列表：筛选条件用 {@code @SpringQueryMap} 展开成 query string，不改变订单服务的参数名。 */
  @GetMapping("/api/v1/admin/orders")
  feign.Response orders(@SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
  /** 订单详情：路径变量显式命名，避免编译期丢失参数名（{@code -parameters} 未开启时也能工作）。 */
  @GetMapping("/api/v1/admin/orders/{orderId}")
  feign.Response order(@PathVariable("orderId") String orderId, @SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
  /** 自提凭证查单：凭证放在 body（POST）而不是 URL，防止被日志与浏览器历史留存。 */
  @PostMapping("/api/v1/admin/pickups/lookup")
  feign.Response lookup(@RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 自提核销（终态）：必须由调用方带上 {@code Idempotency-Key}，否则订单服务无法保证不重复交付。 */
  @PostMapping("/api/v1/admin/orders/{orderId}/pickup")
  feign.Response pickup(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 售后列表：仅店长可调；订单服务会用转发来的 Token 重新判定身份。 */
  @GetMapping("/api/v1/admin/after-sales")
  feign.Response afterSales(@SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
  /** 售后详情：同时接受筛选 query，便于前端带上来源列表的上下文。 */
  @GetMapping("/api/v1/admin/after-sales/{saleId}")
  feign.Response afterSale(@PathVariable("saleId") String saleId, @SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
  /** 发起换宠：用 PUT 表达"替换售后处理方案"，重复提交的语义由下游状态机判定。 */
  @PutMapping("/api/v1/admin/after-sales/{saleId}/exchange")
  feign.Response exchange(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 售后审核裁决：通过或驳回，决定后续走退款/换宠/关闭。 */
  @PostMapping("/api/v1/admin/after-sales/{saleId}/review")
  feign.Response review(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 登记退回宠物：退款的前置动作，未收到宠物不得退款。 */
  @PostMapping("/api/v1/admin/after-sales/{saleId}/returns")
  feign.Response receiveReturn(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 换宠交付完成：切换库存归属并记录交付留痕，属终态动作。 */
  @PostMapping("/api/v1/admin/after-sales/{saleId}/exchange-delivery")
  feign.Response deliverExchange(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 换宠失败转退款：由订单服务按原支付渠道发起，金额不得超实付。 */
  @PostMapping("/api/v1/admin/after-sales/{saleId}/exchange-refund")
  feign.Response refundExchange(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 人工重试退款：仅失败态可重试，已成功/处理中的退款会被下游拒绝以免重复出款。 */
  @PostMapping("/api/v1/admin/refunds/{refundId}/retry")
  feign.Response retryRefund(@PathVariable("refundId") String refundId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  /** 经营看板：统计口径完全由订单服务决定，本服务不缓存、不二次聚合。 */
  @GetMapping("/api/v1/admin/dashboard")
  feign.Response dashboard(@SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
}
