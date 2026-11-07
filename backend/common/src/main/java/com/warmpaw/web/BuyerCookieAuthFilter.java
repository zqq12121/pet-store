package com.warmpaw.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把买家登录 Cookie 转成 {@code Authorization: Bearer} 头。
 *
 * <p>项目里所有身份解析都读 {@code Authorization} 头（{@code AuthService.identify}），
 * 在入口处统一转换，业务代码一行不用改，小程序和管理端的 Bearer 用法也不受影响。
 *
 * <p>以下情况<b>不转换</b>：
 *
 * <ul>
 *   <li>请求已带 {@code Authorization} 或 {@code X-Guest-Token}——显式身份优先，
 *       避免与游客身份同时出现被判为歧义；
 *   <li>{@code /api/v1/admin/**}——管理端只认自己的 Bearer，买家 Cookie 不能顶替；
 *   <li>{@code /api/v1/auth/**}（退出登录除外）——登录类接口不需要身份，
 *       这样"密码输错"之类的 401 不会被误当成会话失效而清掉有效 Cookie。
 * </ul>
 *
 * <p>转换后的请求如果返回 401，只可能是这条会话失效（过期、退出、改密撤销），
 * 响应里顺手下发删除 Cookie，下一次请求按匿名处理，公开页面不会一直报 401。
 * 放在过滤器里拦 {@code setStatus}，而不是写进异常处理器：项目里有两个
 * {@code @RestControllerAdvice} 都处理 {@code ApiException}，写在这里只需一处。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BuyerCookieAuthFilter extends OncePerRequestFilter {
  private final BuyerSessionCookie cookie;

  public BuyerCookieAuthFilter(BuyerSessionCookie cookie) {
    this.cookie = cookie;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = BuyerSessionCookie.read(request);
    if (token == null || !applies(request)) {
      chain.doFilter(request, response);
      return;
    }
    String bearer = "Bearer " + token;
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public String getHeader(String name) {
            return "Authorization".equalsIgnoreCase(name) ? bearer : super.getHeader(name);
          }

          @Override
          public Enumeration<String> getHeaders(String name) {
            return "Authorization".equalsIgnoreCase(name)
                ? Collections.enumeration(List.of(bearer))
                : super.getHeaders(name);
          }
        },
        new HttpServletResponseWrapper(response) {
          @Override
          public void setStatus(int status) {
            if (status == 401 && !isCommitted()) addHeader(HttpHeaders.SET_COOKIE, cookie.clear());
            super.setStatus(status);
          }
        });
  }

  private static boolean applies(HttpServletRequest request) {
    if (request.getHeader("Authorization") != null || request.getHeader("X-Guest-Token") != null)
      return false;
    String path = request.getRequestURI();
    if (path.startsWith("/api/v1/admin/")) return false;
    return !path.startsWith("/api/v1/auth/") || path.equals("/api/v1/auth/logout");
  }
}
