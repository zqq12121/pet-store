package com.warmpaw.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个 HTTP 请求建立 traceId，放进 MDC 供日志格式输出，并在响应头回带。
 *
 * <p><b>为什么需要它</b>：本项目有 3 个 JVM（gateway / admin / order）和一次 Feign 转发。
 * 出问题时只有异常堆栈和数据库，看不出「这个请求经过了哪几个服务、在哪一步失败」。
 * 有了 traceId，只要在网关那一端拿到响应头的值，就能把所有服务的日志筛成一条时间线：
 *
 * <pre>{@code
 * docker logs admin-server 2>&1 | grep '\[a1b2c3d4e5f60718\]'
 * }</pre>
 *
 * <p><b>为什么只信任上游传来的值、不无条件透传</b>：{@code X-Trace-Id} 来自请求头，
 * 是外部可控输入。如果原样写进 MDC 并输出到日志，攻击者可以塞入换行符伪造出额外的日志行
 * （日志注入，可用来掩盖痕迹或污染审计）。所以这里只接受
 * {@code [A-Za-z0-9._-]} 且长度不超过 64 的字符，其余一律重新生成。
 *
 * <p><b>为什么在 finally 里 {@code MDC.remove}</b>：Servlet 容器复用工作线程。
 * 不清理的话，下一个请求（可能是另一个用户、另一个 traceId）会把上一个请求的 traceId
 * 带进日志，出现「日志里的 traceId 与响应头不一致」这种最难排查的错位。
 *
 * <p><b>顺序</b>：{@link Ordered#HIGHEST_PRECEDENCE} 让它尽量早执行，
 * 保证后续过滤器、拦截器、Controller 里打的日志都已经有 traceId 可用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  /** 请求与响应共用的头名；网关与 Feign 都按这个名字透传。 */
  public static final String HEADER = "X-Trace-Id";

  /** MDC 键名，必须与 {@code logback-spring.xml} 里的 {@code %X{traceId}} 保持一致。 */
  public static final String MDC_KEY = "traceId";

  /** 上游允许传入选用值的最大长度；超过则视为不可信并重新生成。 */
  private static final int MAX_LENGTH = 64;

  /** 只允许这些字符，从根本上排除换行、制表符等可用于伪造日志行的字符。 */
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]+");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = resolve(request);
    MDC.put(MDC_KEY, traceId);
    // 响应头回带，便于前端或调用方在报错时直接报出 traceId，而不必翻日志。
    response.setHeader(HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /**
   * 取上游 traceId，不合法就新生成一个。
   *
   * <p>生成用 UUID 去掉横线后截前 16 位：足够避免碰撞，又比完整 UUID 短一半，
   * 日志里不至于把有效信息挤到屏幕外。
   */
  private String resolve(HttpServletRequest request) {
    String incoming = request.getHeader(HEADER);
    if (incoming != null
        && !incoming.isBlank()
        && incoming.length() <= MAX_LENGTH
        && SAFE.matcher(incoming).matches()) {
      return incoming;
    }
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
