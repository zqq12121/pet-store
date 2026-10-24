package com.warmpaw.web;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * HTTP 错误不伪装为 200；内部异常不泄露 SQL、路径、令牌与堆栈。
 *
 * <p><b>与 {@code controller.support.GlobalExceptionHandler} 的关系</b>：本类是它的早期版本，
 * 少一个 404/405 映射，其余处理器与响应体逐行相同。两个类都在 {@code com.warmpaw} 扫描范围内，
 * 会同时注册成 {@code @RestControllerAdvice}；同一异常由谁处理取决于 advice 的先后顺序，
 * 但两者输出结构一致，因此外部观察不到差异。差异只出现在
 * {@code NoHandlerFoundException}/{@code NoResourceFoundException}/{@code HttpMethodNotSupported}：
 * 本类没有对应处理器，会落到兜底的 500。
 *
 * <p>实际生效的是 {@code GlobalExceptionHandler}（它更完整）；本类没有显式引用点，
 * 仅靠组件扫描注册。
 *
 * <p><b>状态码语义</b>：400 参数/JSON 错、409 唯一键冲突、413 文件过大、500 兜底。
 * 业务错误的状态码由 {@code ApiException} 自带，原样透出。
 */
@RestControllerAdvice
public class ApiErrors {
  /** 业务异常：{@code status}/{@code code} 由抛出点决定，文案直接给用户看。 */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<?> business(ApiException e) {
    return error(e.status, e.code, e.getMessage());
  }

  /** 请求体不可解析、缺必填参数、参数类型不匹配——统一按"请求读不懂"处理。 */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return error(400, "VALIDATION_ERROR", "请求字段或 JSON 格式不正确");
  }

  /** 超过容器允许的上传大小；用 413 让前端能针对性提示"文件太大"。 */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> large(Exception e) {
    return error(413, "FILE_TOO_LARGE", "文件超过上传大小限制");
  }

  /**
   * 唯一键冲突（业务键重复、并发插入同一幂等键）。
   *
   * <p>借用 {@code VERSION_CONFLICT} 业务码：客户端对"已存在"和"被别人改过"的处置都是
   * "重新加载后重试"，不需要区分。
   */
  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  public ResponseEntity<?> conflict(Exception e) {
    return error(409, "VERSION_CONFLICT", "资源已存在或被其他请求占用");
  }

  /**
   * 兜底 500：只记录异常类名，不记录 message 与堆栈。
   *
   * <p>这是防止 SQL 片段、文件路径、token 顺着日志或响应外泄的最后一道闸；
   * 代价是排查只能靠类名 + 其它业务日志。
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unknown(Exception e) {
    org.slf4j.LoggerFactory.getLogger(ApiErrors.class)
        .error("请求处理失败，异常类型={}", e.getClass().getSimpleName());
    return error(500, "INTERNAL_ERROR", "服务暂不可用，请稍后重试");
  }

  /**
   * 统一错误体 + 响应头。
   *
   * <p>{@code errors} 只在 {@code VALIDATION_ERROR} 时出现，结构
   * {@code [{field,reason}]}：为将来按字段报错预留；现在统一 {@code field="request"}，
   * 因为多数校验在 {@code Input} 里合并成一条消息。
   *
   * <p>{@code Retry-After} 对 429 给 60 秒，其它状态给 {@code "0"}（明确"无需退避"）。
   * {@code requestId} 是本次错误新生成的，不与入站请求关联，仅用于用户报障时对齐。
   */
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
