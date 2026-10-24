package com.warmpaw.remote;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/**
 * 服务调用失败返回 503，不自动重试交易写请求，也不泄露下游地址或令牌。
 *
 * <p>只作用于 {@link AdminTransactionController}（{@code assignableTypes}），
 * 不影响 admin-server 自己的业务异常——那些仍由全局处理器按 4xx 返回。
 *
 * <p>{@code @Order(-1)} 让它先于通用异常处理器执行，否则 Feign 异常会被包装成 500，
 * 前端就无法区分"订单服务连不上"与"本服务内部错误"。
 *
 * <p>为什么不重试：这里的写请求包含核销、退款、审核，重试可能造成二次交付或重复出款；
 * 需要重试语义的操作由订单服务的幂等键负责，而不是在传输层偷偷重放。
 *
 * <p>为什么不透传原始异常信息：{@code FeignException} 的 message 可能含下游 URL、路径参数
 * 甚至请求头，直接回给浏览器等于泄露内网拓扑与令牌。
 */
@RestControllerAdvice(assignableTypes = AdminTransactionController.class)
@Order(-1)
public class RemoteOrderExceptionHandler {
  /**
   * Feign 调用失败（连接拒绝、超时、解码异常等）统一降级为 503。
   *
   * <p>响应体固定为 {@code ORDER_SERVICE_UNAVAILABLE} 与一句用户可读的中文提示，
   * <b>不回显下游地址、请求路径或原始异常</b>；也刻意不给 {@code Retry-After}，
   * 因为自动重试写请求不安全，应由操作人员确认订单状态后再发起。
   *
   * <p>状态码选 503 而非 500：这是依赖不可用，属于可恢复的临时故障，前端可提示"稍后重试"。
   */
  @ExceptionHandler(feign.FeignException.class)
  public ResponseEntity<?> unavailable(feign.FeignException error) {
    return ResponseEntity.status(503).body(Map.of("code", "ORDER_SERVICE_UNAVAILABLE",
        "message", "订单服务暂不可用，请稍后重试"));
  }
}
