package com.warmpaw.remote;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;
/** 后台交易客户端：服务名经 Nacos 解析；原 Token 和幂等键显式透传。 */
@FeignClient(name = "order-server", url = "${services.order.url:}")
public interface OrderAdminClient {
  /** 预约操作透传原 Token 和幂等键。 */
  @PostMapping("/api/v1/admin/orders/{orderId}/appointment/{action}")
  feign.Response appointment(@PathVariable("orderId") String orderId, @PathVariable("action") String action,
      @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @GetMapping("/api/v1/admin/orders")
  feign.Response orders(@SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
  @GetMapping("/api/v1/admin/orders/{orderId}")
  feign.Response order(@PathVariable("orderId") String orderId, @SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
  @PostMapping("/api/v1/admin/pickups/lookup")
  feign.Response lookup(@RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @PostMapping("/api/v1/admin/orders/{orderId}/pickup")
  feign.Response pickup(@PathVariable("orderId") String orderId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @GetMapping("/api/v1/admin/after-sales")
  feign.Response afterSales(@SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
  @GetMapping("/api/v1/admin/after-sales/{saleId}")
  feign.Response afterSale(@PathVariable("saleId") String saleId, @SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
  @PutMapping("/api/v1/admin/after-sales/{saleId}/exchange")
  feign.Response exchange(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @PostMapping("/api/v1/admin/after-sales/{saleId}/review")
  feign.Response review(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @PostMapping("/api/v1/admin/after-sales/{saleId}/returns")
  feign.Response receiveReturn(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @PostMapping("/api/v1/admin/after-sales/{saleId}/exchange-delivery")
  feign.Response deliverExchange(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @PostMapping("/api/v1/admin/after-sales/{saleId}/exchange-refund")
  feign.Response refundExchange(@PathVariable("saleId") String saleId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @PostMapping("/api/v1/admin/refunds/{refundId}/retry")
  feign.Response retryRefund(@PathVariable("refundId") String refundId, @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers);
  @GetMapping("/api/v1/admin/dashboard")
  feign.Response dashboard(@SpringQueryMap Map<String, Object> query, @RequestHeader Map<String, String> headers);
}
