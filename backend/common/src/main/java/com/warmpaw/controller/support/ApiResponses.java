package com.warmpaw.controller.support;

import static com.warmpaw.common.Json.*;

import java.time.Instant;
import org.springframework.http.*;

/**
 * HTTP 成功响应统一包装，保持现有前端契约和禁止缓存策略。
 *
 * <p>成功响应的信封是 {@code {code:"OK", message:"success", data, requestId, serverTime}}，
 * 与错误响应共用同一组键（错误时 {@code data} 为 null、{@code code} 为业务错误码）。
 * 前端因此可以只写一套解析：先看 HTTP 状态，再读 {@code code}。
 *
 * <p><b>{@code no-store}</b>：所有响应都禁用缓存。接口数据里含价格、库存、订单状态，
 * 一旦被浏览器或中间层缓存，用户会看到过期的"可购买"并下单失败——
 * 这类问题比多几次请求昂贵得多。
 *
 * <p><b>状态码由业务结果决定</b>（{@code result.status()}，可能是 200/201/202），
 * 而不是固定 200：202 明确表示"已受理、异步推进中"，客户端据此决定是否轮询。
 */
public final class ApiResponses {
  /** 工具类，禁止实例化。 */
  private ApiResponses() {}

  /**
   * 构造成功信封（不设状态码，默认由调用方决定）。
   *
   * <p>{@code requestId} 每次新生成，<b>不是</b>入站请求的追踪 id，也不与日志关联；
   * 它的用途是让用户报障时能报出一个可检索的串。
   */
  public static com.warmpaw.dto.ApiResponse<Object> envelope(Object data) {
    return new com.warmpaw.dto.ApiResponse<>(
        "OK", "success", data, id("req"), Instant.now().toString());
  }

  /**
   * 组装最终响应：指定状态码 + 禁缓存 + 统一信封。
   *
   * <p>{@code data} 允许为 null（例如纯动作型接口），信封结构不变，
   * 前端不必为"没有返回体"的接口写特例。
   */
  public static ResponseEntity<Object> ok(int status, Object data) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(envelope(data));
  }
}
