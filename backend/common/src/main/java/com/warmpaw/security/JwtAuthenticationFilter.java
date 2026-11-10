package com.warmpaw.security;

import com.warmpaw.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：解析 {@code Authorization: Bearer <jwt>}，成功后把当前用户放进 SecurityContext。
 *
 * <p>principal 是 {@link AuthService.Actor}，权限为 {@code ROLE_BUYER / ROLE_STAFF / ROLE_ADMIN}。
 * 业务入口（{@code ApiRequestExecutor}）直接从 SecurityContext 取用户，不再重复解析。
 *
 * <p><b>token 无效时不在这里写响应</b>：只清空上下文、让请求继续往下走。业务入口发现上下文为空
 * 但带了 Authorization 头，会再调用 {@code identify} 抛出 401，经全局异常处理器输出统一的
 * {@code {code, message, ...}} 响应体——保证错误格式与其他业务错误一致，前端只需一套处理。
 *
 * <p>不注册为 Spring Bean（不加 {@code @Component}），否则 Spring Boot 会把它再注册成普通
 * Servlet 过滤器，每个请求执行两遍。它只在 {@link SecurityConfig} 的过滤器链里出现。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final AuthService auth;

  public JwtAuthenticationFilter(AuthService auth) {
    this.auth = auth;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      try {
        AuthService.Actor actor = auth.identify(header);
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                actor,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().toUpperCase(Locale.ROOT))));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (RuntimeException e) {
        // 过期、伪造、已撤销：交给业务入口抛出统一格式的 401（见类注释）
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(request, response);
  }
}
