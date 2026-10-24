package com.warmpaw.controller.support;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.ApiException;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * HTTP 错误不伪装为 200；内部异常不泄露 SQL、路径、令牌与堆栈。
 *
 * <p><b>统一错误体</b>：{@code {code, message, data:null, requestId, serverTime}}，
 * 与成功响应共用同一个信封形状（成功时 {@code code="OK"}），前端只需一套解析逻辑。
 * 校验类错误额外带 {@code errors:[{field,reason}]}，为将来"按字段报错"留好结构，
 * 目前统一填 {@code field="request"}（因为大部分校验发生在 {@code Input} 里，拿不到具体字段）。
 *
 * <p><b>HTTP 状态 = 业务语义</b>，不是统一 200：400 参数错、401 未登录、403 无权限、
 * 404 不存在、409 冲突、413 文件过大、422 业务前提不满足、429 限流、502 上游故障、503 未配置。
 * 这样网关、监控、CDN 都能按状态码做正确统计，客户端也不必解析 body 才知道失败。
 *
 * <p><b>信息泄露控制</b>：兜底的 {@code Exception} 分支只记<b>异常类名</b>，不记 message、
 * 不记堆栈、不把异常内容写进响应。代价是排查 500 时只能靠类名去定位，
 * 但比起把 SQL 片段、文件路径、token 带进日志和响应，这个代价是划算的。
 *
 * <p><b>与 {@code com.warmpaw.web.ApiErrors} 的关系</b>：两者内容几乎相同，且所在包都在
 * {@code scanBasePackages="com.warmpaw"} 范围内，因此会被同时注册为 {@code @RestControllerAdvice}。
 * 同一异常最终由哪个 advice 处理取决于先后顺序，但两者的响应体结构完全一致，
 * 外部不可见差异；区别只在于 404/405 的映射是<b>本类独有</b>的（{@code ApiErrors} 没有这一段）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  /**
   * 业务异常：状态码与业务码由抛出处决定，原样透出。
   *
   * <p>这是唯一一条"错误文案由业务代码掌握"的通道，所以文案必须是对用户可读的中文，
   * 且不含内部细节。
   */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<?> business(ApiException e) {
    return error(e.status, e.code, e.getMessage());
  }

  /**
   * 请求体/参数形态错误：JSON 解析失败、缺必填 query 参数、参数类型不匹配。
   *
   * <p>三者合并成同一个 400：对客户端来说都是"你发的请求我读不懂"，
   * 细分错误码没有实际收益，反而会暴露框架内部的异常类型。
   */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<?> invalid(Exception e) {
    return error(400, "VALIDATION_ERROR", "请求字段或 JSON 格式不正确");
  }

  /** 上传超过容器限制：413 而不是 400，让前端能区分"文件太大"并提前压缩。 */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> large(Exception e) {
    return error(413, "FILE_TOO_LARGE", "文件超过上传大小限制");
  }

  /**
   * 唯一键冲突：{@code business_key} 重复（手机号已注册、用户名被占用）或
   * 并发下同一幂等键被插入两次。
   *
   * <p>业务码借用 {@code VERSION_CONFLICT}：对客户端而言"资源已存在"和"数据已被别人改过"
   * 的处置完全一样（重新加载再试），所以不单独开一个码。
   */
  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  public ResponseEntity<?> conflict(Exception e) {
    return error(409, "VERSION_CONFLICT", "资源已存在或被其他请求占用");
  }

  /**
   * 路径不存在或方法不支持：统一 404 {@code RESOURCE_NOT_FOUND}。
   *
   * <p><b>方法不支持也回 404 而不是 405</b>：这是刻意的——对调用方来说
   * "这个 URL 上没有可用的接口"与"这个 URL 根本不存在"没有区别，
   * 少暴露一个 405 就少一个探测"哪些路径存在、支持什么方法"的信号。
   */
  @ExceptionHandler({
    org.springframework.web.servlet.NoHandlerFoundException.class,
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.HttpRequestMethodNotSupportedException.class
  })
  public ResponseEntity<?> notFound(Exception e) {
    return error(404, "RESOURCE_NOT_FOUND", "接口不存在");
  }

  /**
   * 兜底：任何未预料的异常都变成 500，且<b>只记录异常类名</b>。
   *
   * <p>注意它同时承担了"把意外异常挡在响应之外"的职责：没有这一层，
   * Spring 默认错误响应会带上异常 message 与路径，可能泄露表名、文件路径或 SQL。
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unknown(Exception e) {
    org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
        .error("请求处理失败，异常类型={}", e.getClass().getSimpleName());
    return error(500, "INTERNAL_ERROR", "服务暂不可用，请稍后重试");
  }

  /**
   * 统一错误响应构造。
   *
   * <p>三个响应头/属性都有明确目的：
   *
   * <ul>
   *   <li>{@code Cache-Control: no-store} —— 错误响应（尤其带 {@code requestId}）不能被缓存，
   *       否则用户会反复看到同一次失败；
   *   <li>{@code Retry-After} —— 429 给 60 秒，让客户端知道退避节奏；
   *       其它状态给 {@code "0"}，表示"不需要退避"（而不是省略头）；
   *   <li>{@code requestId} —— 每次错误新生成，便于用户报障时与服务端日志对齐。
   *       <b>它不是请求的追踪 id</b>，不与入站请求头关联，只用于这一次错误响应。
   * </ul>
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
