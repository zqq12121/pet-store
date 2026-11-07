package com.warmpaw.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 买家网站的登录态 Cookie。
 *
 * <p>{@code HttpOnly}：前端 JS 读不到 token，XSS 无法把登录态偷走；浏览器同源请求自动携带，
 * 关掉标签页或重启浏览器后仍保持登录，直到会话到期（买家 24 小时）。
 *
 * <p>{@code SameSite=Lax}：跨站的 POST/PUT/DELETE 子请求不带 Cookie，挡住 CSRF；
 * 只有跨站顶层 GET 导航会带上，而 GET 接口都是只读的。
 *
 * <p>{@code Secure} 由 {@code app.auth-cookie-secure}（环境变量 {@code PAW_AUTH_COOKIE_SECURE}）决定：
 * 边缘 Caddy 终结 HTTPS 后，内部 nginx 会把 {@code X-Forwarded-Proto} 改写成 http，
 * 后端无法自行判断，所以只在 HTTPS 部署（compose.production.yaml）里显式打开。
 *
 * <p>管理端和小程序不用 Cookie，继续走 {@code Authorization: Bearer}。
 */
@Component
public class BuyerSessionCookie {
  public static final String NAME = "warmpaw_buyer";
  private static final String PATH = "/api";

  private final boolean secure;

  public BuyerSessionCookie(@Value("${app.auth-cookie-secure:false}") boolean secure) {
    this.secure = secure;
  }

  /** 登录成功后下发；Max-Age 与服务端会话有效期一致，两边同时到期。 */
  public String issue(String token, long seconds) {
    return build(token, Duration.ofSeconds(seconds));
  }

  /** 退出登录或会话失效时下发，让浏览器立即删除。 */
  public String clear() {
    return build("", Duration.ZERO);
  }

  private String build(String value, Duration maxAge) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path(PATH)
        .maxAge(maxAge)
        .build()
        .toString();
  }

  /** 读取请求里的登录 Cookie，没有或为空时返回 null。 */
  public static String read(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return null;
    for (Cookie c : cookies)
      if (NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank())
        return c.getValue();
    return null;
  }
}
