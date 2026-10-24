package com.warmpaw.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关侧的 traceId 生成与透传，是整条调用链的起点。
 *
 * <p><b>为什么必须由网关生成</b>：三个 Java 服务里，只有网关是外部请求的唯一入口。
 * 由它统一发号，admin-server 转发给 order-server 时才可能用同一个 id。
 * 如果让每个服务各自生成，一次下单会得到 3 个互不相关的 traceId，等于没做。
 *
 * <p><b>为什么不使用 MDC</b>：这是 WebFlux（Reactor）线程模型，
 * 一个请求的执行会在线程间迁移，{@code MDC} 的 ThreadLocal 语义在这里不成立。
 * 因此本类把 traceId 作为<b>参数</b>直接传给日志，而不是塞进 MDC。
 * 业务服务是 Servlet 模型，那边才用 MDC（见 {@code com.warmpaw.web.TraceIdFilter}）。
 *
 * <p><b>安全</b>：{@code X-Trace-Id} 来自请求头，属外部可控输入。
 * 只接受 {@code [A-Za-z0-9._-]} 且长度 ≤ 64，防止换行符造成日志注入。
 */
@Component
public class TraceIdGatewayFilter implements GlobalFilter, Ordered {

  /** 与业务服务、Feign 拦截器共用同一个头名。 */
  public static final String HEADER = "X-Trace-Id";

  private static final Logger log = LoggerFactory.getLogger(TraceIdGatewayFilter.class);

  /** 上游允许传入选用值的最大长度。 */
  private static final int MAX_LENGTH = 64;

  /** 白名单字符，排除换行与制表符。 */
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]+");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String traceId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));

    // 改写请求头：下游服务（admin/order/ai）据此沿用同一个 traceId。
    ServerHttpRequest request = exchange.getRequest().mutate().header(HEADER, traceId).build();
    // 响应头也带上：前端报错截图里能直接看到 traceId，省去「翻日志找请求」这一步。
    exchange.getResponse().getHeaders().set(HEADER, traceId);

    long startNanos = System.nanoTime();
    String path = request.getURI().getPath();
    String method = request.getMethod().name();

    return chain
        .filter(exchange.mutate().request(request).build())
        .doFinally(
            signal -> {
              // 状态码在客户端断开时可能为 null，用 "-" 占位而不是让它 NPE。
              var status = exchange.getResponse().getStatusCode();
              long millis = (System.nanoTime() - startNanos) / 1_000_000;
              log.info(
                  "{} {} -> {} {}ms (signal={}) trace={}",
                  method,
                  path,
                  status == null ? "-" : status.value(),
                  millis,
                  signal,
                  traceId);
            });
  }

  /**
   * 取上游 traceId，不合法就新生成。
   *
   * <p>用 UUID 去横线取前 16 位：避免碰撞，同时短到不会把日志正文挤出屏幕。
   */
  private String resolve(String incoming) {
    if (incoming != null
        && !incoming.isBlank()
        && incoming.length() <= MAX_LENGTH
        && SAFE.matcher(incoming).matches()) {
      return incoming;
    }
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  /** 必须早于路由转发执行，否则下游拿不到头。 */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
