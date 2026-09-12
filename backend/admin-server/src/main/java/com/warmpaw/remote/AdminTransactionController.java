package com.warmpaw.remote;
import com.warmpaw.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
/** 管理入口只做鉴权与远程调用；不持数据库写锁，不重复执行订单业务。 */
@RestController
@RequestMapping("/api/v1")
public class AdminTransactionController {
  private final OrderAdminClient orders;
  private final AuthService auth;
  public AdminTransactionController(OrderAdminClient orders, AuthService auth) {
    this.orders = orders; this.auth = auth;
  }
  private Map<String, String> headers(HttpServletRequest request) {
    String token = request.getHeader("Authorization");
    AuthService.role(auth.identify(token), "admin");
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", token);
    if (request.getHeader("Idempotency-Key") != null)
      headers.put("Idempotency-Key", request.getHeader("Idempotency-Key"));
    return headers;
  }
  /** 原样保留订单服务的 HTTP 状态、业务错误和重试提示，避免把错误包装成成功。 */
  private ResponseEntity<byte[]> relay(feign.Response response) throws java.io.IOException {
    try (response) {
      var result = ResponseEntity.status(response.status());
      response.headers().forEach((name, values) -> {
        if (List.of("content-type", "cache-control", "retry-after").contains(name.toLowerCase(Locale.ROOT)))
          result.header(name, values.toArray(String[]::new));
      });
      return result.body(response.body() == null ? new byte[0] : response.body().asInputStream().readAllBytes());
    }
  }
  /** GET /admin/orders：由订单服务执行并再次校验管理员身份。 */
  @GetMapping("/admin/orders")
  public ResponseEntity<byte[]> orders(@RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.orders(query, headers(request)));
  }
  /** GET /admin/orders/{orderId}：由订单服务执行并再次校验管理员身份。 */
  @GetMapping("/admin/orders/{orderId}")
  public ResponseEntity<byte[]> order(@PathVariable("orderId") String orderId, @RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.order(orderId, query, headers(request)));
  }
  /** POST /admin/pickups/lookup：由订单服务执行并再次校验管理员身份。 */
  @PostMapping("/admin/pickups/lookup")
  public ResponseEntity<byte[]> lookup(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.lookup(body == null ? Map.of() : body, headers(request)));
  }
  /** POST /admin/orders/{orderId}/pickup：由订单服务执行并再次校验管理员身份。 */
  @PostMapping("/admin/orders/{orderId}/pickup")
  public ResponseEntity<byte[]> pickup(@PathVariable("orderId") String orderId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.pickup(orderId, body == null ? Map.of() : body, headers(request)));
  }
  /** GET /admin/after-sales：由订单服务执行并再次校验管理员身份。 */
  @GetMapping("/admin/after-sales")
  public ResponseEntity<byte[]> afterSales(@RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.afterSales(query, headers(request)));
  }
  /** GET /admin/after-sales/{saleId}：由订单服务执行并再次校验管理员身份。 */
  @GetMapping("/admin/after-sales/{saleId}")
  public ResponseEntity<byte[]> afterSale(@PathVariable("saleId") String saleId, @RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.afterSale(saleId, query, headers(request)));
  }
  /** PUT /admin/after-sales/{saleId}/exchange：由订单服务执行并再次校验管理员身份。 */
  @PutMapping("/admin/after-sales/{saleId}/exchange")
  public ResponseEntity<byte[]> exchange(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.exchange(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /** POST /admin/after-sales/{saleId}/review：由订单服务执行并再次校验管理员身份。 */
  @PostMapping("/admin/after-sales/{saleId}/review")
  public ResponseEntity<byte[]> review(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.review(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /** POST /admin/after-sales/{saleId}/returns：由订单服务执行并再次校验管理员身份。 */
  @PostMapping("/admin/after-sales/{saleId}/returns")
  public ResponseEntity<byte[]> receiveReturn(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.receiveReturn(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /** POST /admin/after-sales/{saleId}/exchange-delivery：由订单服务执行并再次校验管理员身份。 */
  @PostMapping("/admin/after-sales/{saleId}/exchange-delivery")
  public ResponseEntity<byte[]> deliverExchange(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.deliverExchange(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /** POST /admin/after-sales/{saleId}/exchange-refund：由订单服务执行并再次校验管理员身份。 */
  @PostMapping("/admin/after-sales/{saleId}/exchange-refund")
  public ResponseEntity<byte[]> refundExchange(@PathVariable("saleId") String saleId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.refundExchange(saleId, body == null ? Map.of() : body, headers(request)));
  }
  /** POST /admin/refunds/{refundId}/retry：由订单服务执行并再次校验管理员身份。 */
  @PostMapping("/admin/refunds/{refundId}/retry")
  public ResponseEntity<byte[]> retryRefund(@PathVariable("refundId") String refundId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.retryRefund(refundId, body == null ? Map.of() : body, headers(request)));
  }
  /** GET /admin/dashboard：由订单服务执行并再次校验管理员身份。 */
  @GetMapping("/admin/dashboard")
  public ResponseEntity<byte[]> dashboard(@RequestParam Map<String, Object> query, HttpServletRequest request) throws java.io.IOException {
    return relay(orders.dashboard(query, headers(request)));
  }
}
