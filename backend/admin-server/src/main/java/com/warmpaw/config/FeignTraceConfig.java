package com.warmpaw.config;

import com.warmpaw.web.TraceIdFilter;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 让 Feign 出站调用带上当前请求的 traceId。
 *
 * <p><b>为什么必须有这一层</b>：admin-server 收到请求时由 {@link TraceIdFilter} 生成了 traceId，
 * 但它只活在当前线程的 MDC 里。Feign 发起的是一次全新的 HTTP 调用，默认不会带上任何自定义头，
 * 于是 order-server 会另生成一个 traceId——一次「确认预约」在日志里就变成两条互不相关的记录，
 * 链路在服务边界处断掉。
 *
 * <p><b>为什么用全局拦截器而不是在每个方法上声明</b>：Feign 方法的参数里已经手写了
 * {@code Authorization} 与 {@code Idempotency-Key}（见 {@code OrderAdminClient}），
 * 那是业务语义、需要显式可见；traceId 是纯技术透传，不该污染每个方法签名。
 *
 * <p><b>覆盖语义</b>：{@code template.header} 对同名头是<b>替换</b>而非追加，
 * 所以即使调用方手动传了 {@code X-Trace-Id}，也以当前线程的 MDC 为准，
 * 不会出现同一个请求带两个 traceId 的情况。
 */
@Configuration
public class FeignTraceConfig {

  /**
   * 把 MDC 里的 traceId 写进 Feign 请求头。
   *
   * <p>MDC 取不到时<b>什么都不加</b>，不生成新的：此时要么是定时任务等非 HTTP 线程，
   * 要么是链路本身就没有 traceId，下游自己会生成一个，这比在这里编一个更容易排查。
   */
  @Bean
  RequestInterceptor traceIdRequestInterceptor() {
    return template -> {
      String traceId = MDC.get(TraceIdFilter.MDC_KEY);
      if (traceId != null && !traceId.isBlank()) {
        template.header(TraceIdFilter.HEADER, traceId);
      }
    };
  }
}
