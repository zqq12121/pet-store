package com.warmpaw.web;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.service.*;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** HTTP 层只适配响应与身份，交易规则位于 Service；微信通知保留原始请求体。 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final GuestService guests;
  private final AuthService auth;
  private final WechatLoginService wechatLogin;
  private final BusinessService business;
  private final FileService files;
  private final Store store;
  private final PaymentService payments;
  private final PaymentWorker worker;
  private final WechatGateway gateway;
  private final TransactionTemplate tx;

  public ApiController(
      GuestService guests,
      AuthService auth,
      WechatLoginService wechatLogin,
      BusinessService business,
      FileService files,
      Store store,
      PaymentService payments,
      PaymentWorker worker,
      WechatGateway gateway,
      PlatformTransactionManager manager) {
    this.guests = guests;
    this.auth = auth;
    this.wechatLogin = wechatLogin;
    this.business = business;
    this.files = files;
    this.store = store;
    this.payments = payments;
    this.worker = worker;
    this.gateway = gateway;
    tx = new TransactionTemplate(manager);
  }

  public static Map<String, Object> envelope(Object data) {
    return map(
        "code",
        "OK",
        "message",
        "success",
        "data",
        data,
        "requestId",
        id("req"),
        "serverTime",
        Instant.now().toString());
  }

  private ResponseEntity<Object> ok(int status, Object data) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(envelope(data));
  }

  private AuthService.Actor actor(HttpServletRequest request) {
    return auth.identify(request.getHeader("Authorization"));
  }

  @PostMapping("/guest-sessions")
  public ResponseEntity<Object> guest(
      HttpServletRequest req, @RequestBody(required = false) Map<String, Object> b) {
    actor(req);
    new Input(b, "");
    return ok(201, guests.create(req.getRemoteAddr()));
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

  @PostMapping(
      value = {"/files", "/admin/files"},
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Object> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam String purpose,
      @RequestParam(required = false) String orderId,
      HttpServletRequest req) {
    AuthService.Actor a = actor(req);
    AuthService.role(a, req.getRequestURI().contains("/admin/") ? "admin" : "buyer");
    require(
        req.getParameterMap().keySet().stream()
            .allMatch(k -> List.of("purpose", "orderId").contains(k)),
        400,
        "VALIDATION_ERROR",
        "存在未知上传字段");
    return ok(201, files.upload(file, purpose, orderId, a));
  }

  @GetMapping("/files/{id}/access-url")
  public ResponseEntity<Object> access(@PathVariable String id, HttpServletRequest req) {
    return ok(200, files.access(id, actor(req)));
  }

  @GetMapping("/media/{id}")
  public ResponseEntity<?> media(
      @PathVariable String id,
      @RequestParam(required = false) String access,
      HttpServletRequest req) {
    actor(req);
    var path = files.content(id, access);
    Map<String, Object> f = store.get("file", id);
    return ResponseEntity.ok()
        .cacheControl(
            "private".equals(text(f, "visibility"))
                ? CacheControl.noStore()
                : CacheControl.maxAge(Duration.ofHours(1)))
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        .header(
            "Content-Disposition",
            text(f, "mimeType").equals("application/pdf") ? "attachment" : "inline")
        .contentType(MediaType.parseMediaType(text(f, "mimeType")))
        .body(new FileSystemResource(path));
  }

  @PostMapping(value = "/callbacks/wechat-pay/{kind}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> callback(
      @PathVariable String kind, @RequestBody String raw, HttpServletRequest req) {
    try {
      require(List.of("payments", "refunds").contains(kind), 404, "RESOURCE_NOT_FOUND", "通知无效");
      gateway.verify(
          req.getHeader("Wechatpay-Timestamp"),
          req.getHeader("Wechatpay-Nonce"),
          req.getHeader("Wechatpay-Serial"),
          req.getHeader("Wechatpay-Signature"),
          raw);
      Map<String, Object> notification = read(raw), fact = gateway.decrypt(notification);
      tx.execute(
          s -> {
            store.mapper.lock();
            if (kind.equals("payments")) {
              require(
                  "TRANSACTION.SUCCESS".equals(text(notification, "event_type")),
                  400,
                  "INVALID_ARGUMENT",
                  "通知类型无效");
              gateway.checkMerchant(fact);
              Map<String, Object> p = store.byKey("payment", text(fact, "out_trade_no"));
              require(p != null, 400, "INVALID_ARGUMENT", "订单不存在");
              payments.paid(p, fact);
            } else {
              require(
                  List.of("REFUND.SUCCESS", "REFUND.ABNORMAL", "REFUND.CLOSED")
                          .contains(text(notification, "event_type"))
                      && gateway.merchant().equals(text(fact, "mchid")),
                  400,
                  "INVALID_ARGUMENT",
                  "退款通知无效");
              Map<String, Object> r = store.byKey("refund", text(fact, "out_refund_no"));
              require(r != null, 400, "INVALID_ARGUMENT", "退款不存在");
              payments.refunded(r, fact);
            }
            return null;
          });
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      return ResponseEntity.status(e instanceof ApiException a ? a.status : 500)
          .body(map("code", "FAIL", "message", "通知处理失败"));
    }
  }

  @RequestMapping(
      value = "/{*path}",
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE
      })
  public ResponseEntity<Object> dispatch(
      @RequestBody(required = false) Map<String, Object> body,
      @RequestParam Map<String, Object> query,
      HttpServletRequest req) {
    String path = req.getRequestURI().substring("/api/v1".length());
    AuthService.Actor a = actor(req);
    if (path.equals("/analytics/events")) {
      require(
          a == null || req.getHeader("X-Guest-Token") == null,
          400,
          "VALIDATION_ERROR",
          "买家与游客身份只能选一种");
      if (a == null) a = guests.identify(req.getHeader("X-Guest-Token"));
    }
    BusinessService.Reply reply =
        business.execute(
            req.getMethod(),
            path,
            body == null ? map() : body,
            query,
            req.getHeader("Idempotency-Key"),
            a,
            req.getRemoteAddr());
    // 预支付意图已经提交，网络调用失败也能由后台对账恢复，不回滚成不存在的支付单。
    if ("payment".equals(reply.kind()) && req.getMethod().equals("POST")) {
      worker.processPayment(reply.resource(), req.getRemoteAddr());
      reply =
          new BusinessService.Reply(
              reply.status(), payments.view(store.get("payment", reply.resource())));
    }
    return ok(reply.status(), reply.data());
  }
}
