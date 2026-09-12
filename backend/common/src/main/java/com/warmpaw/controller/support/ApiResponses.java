package com.warmpaw.controller.support;

import static com.warmpaw.common.Json.*;

import java.time.Instant;
import org.springframework.http.*;

/** HTTP 成功响应统一包装，保持现有前端契约和禁止缓存策略。 */
public final class ApiResponses {
  private ApiResponses() {}

  public static com.warmpaw.dto.ApiResponse<Object> envelope(Object data) {
    return new com.warmpaw.dto.ApiResponse<>(
        "OK", "success", data, id("req"), Instant.now().toString());
  }

  public static ResponseEntity<Object> ok(int status, Object data) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(envelope(data));
  }
}
