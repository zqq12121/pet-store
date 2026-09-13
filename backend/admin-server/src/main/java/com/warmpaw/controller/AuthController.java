package com.warmpaw.controller;

import static com.warmpaw.common.Json.*;
import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.common.ApiException;
import com.warmpaw.common.Input;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.GuestService;
import com.warmpaw.service.WechatLoginService;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 账号登录、验证码和微信授权接口。 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final GuestService guests;
  private final AuthService auth;
  private final WechatLoginService wechatLogin;

  public AuthController(GuestService guests, AuthService auth, WechatLoginService wechatLogin) {
    this.guests = guests;
    this.auth = auth;
    this.wechatLogin = wechatLogin;
  }

  private AuthService.Actor actor(HttpServletRequest request) {
    return auth.identify(request.getHeader("Authorization"));
  }

  /** AI 服务每次请求复用 Java 身份校验；只返回归属标识，不返回凭证或个人资料。 */
  @GetMapping("/auth/ai-identity")
  public ResponseEntity<Object> aiIdentity(HttpServletRequest req) {
    String bearer = req.getHeader("Authorization"), guest = req.getHeader("X-Guest-Token");
    ApiException.require(bearer == null || guest == null, 400, "AMBIGUOUS_IDENTITY", "不能同时提供两种身份");
    AuthService.Actor a = bearer == null ? guests.identify(guest) : auth.identify(bearer);
    ApiException.require(List.of("buyer", "guest").contains(a.role()), 403, "FORBIDDEN", "请使用买家或游客身份");
    return ok(200, map("id", a.id(), "role", a.role()));
  }

  @PostMapping("/guest-sessions")
  public ResponseEntity<Object> guest(
      HttpServletRequest req, @RequestBody(required = false) Map<String, Object> b) {
    actor(req);
    new Input(b, "");
    return ok(201, guests.create(req.getRemoteAddr()));
  }

  /** 知识后台独立验证管理员，不能复用买家/游客的 AI 身份权限。 */
  @GetMapping("/admin/auth/ai-identity")
  public ResponseEntity<Object> aiAdminIdentity(HttpServletRequest req) {
    ApiException.require(req.getHeader("X-Guest-Token") == null, 403, "FORBIDDEN", "知识管理仅限管理员");
    AuthService.Actor a = actor(req);
    AuthService.role(a, "admin");
    return ok(200, map("id", a.id(), "role", a.role()));
  }

  @GetMapping("/auth/captchas")
  public ResponseEntity<Object> captcha(
      @RequestParam Map<String, Object> query, HttpServletRequest req) {
    actor(req);
    return ok(
        200,
        auth.captcha(
            new Input(query, "purpose").choice("purpose", "sms,admin_login"), req.getRemoteAddr()));
  }

  @PostMapping("/auth/sms-codes")
  public ResponseEntity<Object> sms(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    actor(req);
    return ok(200, auth.sendLoginSms(b, req.getRemoteAddr()));
  }

  @PostMapping("/auth/sms-login")
  public ResponseEntity<Object> login(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    actor(req);
    return ok(200, auth.smsLogin(b));
  }

  @PostMapping("/admin/auth/login")
  public ResponseEntity<Object> adminLogin(
      @RequestBody Map<String, Object> b, HttpServletRequest req) {
    actor(req);
    return ok(200, auth.adminLogin(b, req.getRemoteAddr()));
  }

  @PostMapping({"/auth/logout", "/admin/auth/logout"})
  public ResponseEntity<Object> logout(HttpServletRequest req) {
    AuthService.Actor a;
    try {
      a = actor(req);
    } catch (ApiException e) {
      if (e.status == 401) return ok(200, null);
      throw e;
    }
    if (a != null) AuthService.role(a, req.getRequestURI().contains("/admin/") ? "admin" : "buyer");
    auth.logout(a);
    return ok(200, null);
  }

  @GetMapping("/auth/wechat/authorize-url")
  public ResponseEntity<Object> authorize(
      @RequestParam Map<String, Object> q, HttpServletRequest req, HttpServletResponse res) {
    AuthService.Actor a = actor(req);
    String cookie = auth.randomToken();
    Map<String, Object> result = wechatLogin.authorize(q, a, cookie);
    res.addHeader(
        "Set-Cookie",
        ResponseCookie.from("paw_oauth", cookie)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/api/v1/auth/wechat")
            .maxAge(300)
            .build()
            .toString());
    return ok(200, result);
  }

  @PostMapping("/auth/wechat/login")
  public ResponseEntity<Object> wechat(
      @RequestBody Map<String, Object> b,
      @CookieValue(value = "paw_oauth", required = false) String browser,
      HttpServletRequest req) {
    return ok(200, wechatLogin.login(b, actor(req), browser));
  }

  @PostMapping("/auth/wechat/bind-phone")
  public ResponseEntity<Object> bind(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    actor(req);
    return ok(200, wechatLogin.bindPhone(b));
  }
}
