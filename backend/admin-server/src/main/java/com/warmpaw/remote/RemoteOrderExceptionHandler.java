package com.warmpaw.remote;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** 服务调用失败返回 503，不自动重试交易写请求，也不泄露下游地址或令牌。 */
@RestControllerAdvice(assignableTypes = AdminTransactionController.class)
@Order(-1)
public class RemoteOrderExceptionHandler {
  @ExceptionHandler(feign.FeignException.class)
  public ResponseEntity<?> unavailable(feign.FeignException error) {
    return ResponseEntity.status(503).body(Map.of("code", "ORDER_SERVICE_UNAVAILABLE",
        "message", "订单服务暂不可用，请稍后重试"));
  }
}
