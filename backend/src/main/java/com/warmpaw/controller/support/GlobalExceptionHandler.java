package com.warmpaw.controller.support;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.ApiException;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** HTTP 错误不伪装为 200；内部异常不泄露 SQL、路径、令牌与堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<?> business(ApiException e) {
    return error(e.status, e.code, e.getMessage());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return error(400, "VALIDATION_ERROR", "请求字段或 JSON 格式不正确");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> large(Exception e) {
    return error(413, "FILE_TOO_LARGE", "文件超过上传大小限制");
  }

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  public ResponseEntity<?> conflict(Exception e) {
    return error(409, "VERSION_CONFLICT", "资源已存在或被其他请求占用");
  }

  @ExceptionHandler({
    org.springframework.web.servlet.NoHandlerFoundException.class,
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.HttpRequestMethodNotSupportedException.class
  })
  public ResponseEntity<?> notFound(Exception e) {
    return error(404, "RESOURCE_NOT_FOUND", "接口不存在");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unknown(Exception e) {
    org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
        .error("请求处理失败，异常类型={}", e.getClass().getSimpleName());
    return error(500, "INTERNAL_ERROR", "服务暂不可用，请稍后重试");
  }

  private ResponseEntity<?> error(int status, String code, String message) {
    var body =
        map(
            "code",
            code,
            "message",
            message,
            "data",
            null,
            "requestId",
            id("req"),
            "serverTime",
            Instant.now().toString());
    if (code.equals("VALIDATION_ERROR"))
      body.put("errors", java.util.List.of(map("field", "request", "reason", message)));
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("Retry-After", status == 429 ? "60" : "0")
        .body(body);
  }
}
