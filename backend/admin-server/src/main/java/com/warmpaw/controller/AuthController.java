package com.warmpaw.controller;

import static com.warmpaw.common.Json.*;
import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.common.ApiException;
import com.warmpaw.common.Input;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.GraphCaptchaService;
import com.warmpaw.service.GuestService;
import com.warmpaw.service.WechatLoginService;
import com.warmpaw.service.WechatMiniQrService;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * 账号登录、验证码和微信授权接口（调用方：买家、店长/员工、第三方 AI 服务）。
 *
 * <p>路由前缀 {@code /api/v1}，本类同时承载买家端 {@code /auth/**}、{@code /me/**} 与
 * 管理端 {@code /admin/auth/**} 三组入口。
 *
 * <p><b>权限模型：没有统一的拦截器</b>，每个方法自行决定：
 *
 * <ul>
 *   <li>登录类入口（sms-login、password-login、admin-login、password-reset、capabilities、
 *       graph-captcha）完全公开，靠图形验证码、短信验证码与失败计数防刷；
 *   <li>其余方法先调用 {@link #actor} 解析身份。{@code AuthService.identify} 在没有
 *       {@code Authorization} 头时返回 {@code null}（匿名合法），所以"调用了 actor(req) 却
 *       不使用返回值"只表示"若带了 token 就必须有效"，并不代表强制登录；
 *   <li>真正要求登录/角色的地方在 Service 层：{@code AuthService.role(...)} 用于买家或店长，
 *       {@code AuthService.staff(...)} 用于店长+员工。
 * </ul>
 *
 * <p>委托：{@link GuestService}（游客身份）、{@link AuthService}（会话与账号安全）、
 * {@link WechatLoginService}（公众号/网站扫码）、{@link GraphCaptchaService}（图形验证码）、
 * {@link WechatMiniQrService}（小程序扫码可用性探测）。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final GuestService guests;
  private final AuthService auth;
  private final WechatLoginService wechatLogin;
  private final GraphCaptchaService graphCaptcha;
  private final WechatMiniQrService miniQr;

  public AuthController(GuestService guests, AuthService auth, WechatLoginService wechatLogin, GraphCaptchaService graphCaptcha, WechatMiniQrService miniQr) {
    this.guests = guests;
    this.auth = auth;
    this.wechatLogin = wechatLogin;
    this.graphCaptcha = graphCaptcha;
    this.miniQr = miniQr;
  }

  /**
   * 图形验证码配置（公开）。
   *
   * <p>只发布图形认证的公开 ID，真正放行由各业务接口内的二次验证决定：本接口只给出
   * 前端渲染所需的开关与元信息，不含任何密钥，因此允许匿名调用。
   *
   * <p>GET {@code /auth/graph-captcha}，无鉴权、无副作用、可重复调用。
   *
   * @return 200 与验证码配置对象
   */
  @GetMapping("/auth/graph-captcha")
  public ResponseEntity<Object> graphCaptcha() {
    return ok(200, graphCaptcha.config());
  }

  /**
   * 解析当前请求的身份，供各方法自行决定是否强制登录。
   *
   * <p>未带 {@code Authorization} 头时返回 {@code null}（匿名合法）；带了口但格式错、
   * 会话不存在、已撤销或已过期则抛 401。这个"null 与 401 不同"的约定是本类权限判断的基础。
   */
  private AuthService.Actor actor(HttpServletRequest request) {
    return auth.identify(request.getHeader("Authorization"));
  }

  /**
   * AI 服务的身份查询：把买家或游客令牌换成可缓存的归属标识。
   *
   * <p>AI 服务每次请求复用 Java 身份校验；只返回归属标识，不返回凭证或个人资料。
   *
   * <p><b>鉴权</b>：两种身份二选一——{@code Authorization: Bearer} 走 {@link AuthService#identify}，
   * {@code X-Guest-Token} 走 {@link GuestService#identify}。同时提供两者直接 400
   * {@code AMBIGUOUS_IDENTITY}，避免"用游客身份绕过买家额度"这类歧义。
   * 解析后仍要求角色是 {@code buyer} 或 {@code guest}（403），拒绝管理端令牌。
   *
   * <p>无副作用、可重复调用；游客令牌过期返回 401 {@code TOKEN_EXPIRED}。
   *
   * @return 200，仅含 {@code id} 与 {@code role}
   */
  @GetMapping("/auth/ai-identity")
  public ResponseEntity<Object> aiIdentity(HttpServletRequest req) {
    String bearer = req.getHeader("Authorization"), guest = req.getHeader("X-Guest-Token");
    ApiException.require(bearer == null || guest == null, 400, "AMBIGUOUS_IDENTITY", "不能同时提供两种身份");
    AuthService.Actor a = bearer == null ? guests.identify(guest) : auth.identify(bearer);
    ApiException.require(List.of("buyer", "guest").contains(a.role()), 403, "FORBIDDEN", "请使用买家或游客身份");
    return ok(200, map("id", a.id(), "role", a.role()));
  }

  /**
   * 签发游客身份（消费统计与 AI 会话用）。
   *
   * <p>POST {@code /guest-sessions}，语义是"创建一个匿名会话"，因此必须可匿名调用：
   * 这里的 {@code actor(req)} 只用于校验"若客户端已带 token，则该 token 必须有效"，
   * 返回值被丢弃，不要求登录。
   *
   * <p>请求体白名单为空——任何字段都会 400，防止客户端试图指定游客 id。
   * 限流按来源 IP（每小时 20 次），令牌有效期 24 小时。
   *
   * @return 201 与 {@code guestToken}/{@code expiresAt}
   */
  @PostMapping("/guest-sessions")
  public ResponseEntity<Object> guest(
      HttpServletRequest req, @RequestBody(required = false) Map<String, Object> b) {
    actor(req);
    new Input(b, "");
    return ok(201, guests.create(req.getRemoteAddr()));
  }

  /**
   * 知识库后台的身份查询（独立于买家/游客路径）。
   *
   * <p>知识后台独立验证管理员，不能复用买家/游客的 AI 身份权限：显式拒绝
   * {@code X-Guest-Token}（403），只接受 Bearer 令牌，且 {@code AuthService.role(a, "admin")}
   * 要求角色严格等于店长——员工令牌同样 403。
   *
   * <p>未登录/令牌失效由 {@code identify} 抛 401。无副作用，可重复调用。
   *
   * @return 200，仅含管理员的 {@code id} 与 {@code role}
   */
  @GetMapping("/admin/auth/ai-identity")
  public ResponseEntity<Object> aiAdminIdentity(HttpServletRequest req) {
    ApiException.require(req.getHeader("X-Guest-Token") == null, 403, "FORBIDDEN", "知识管理仅限管理员");
    AuthService.Actor a = actor(req);
    AuthService.role(a, "admin");
    return ok(200, map("id", a.id(), "role", a.role()));
  }

  /**
   * 获取图形验证码（PNG base64）。
   *
   * <p>GET {@code /auth/captchas?purpose=sms|admin_login|password_login}。purpose 之外的取值
   * 直接 400，防止调用方自定义用途绕过后续校验；同一 IP 每分钟最多 30 次。
   *
   * <p>鉴权：只做"可选身份校验"（actor 返回值未使用），未登录也可获取——登录页本身就需要它。
   * 每次调用都会生成新验证码，不幂等；明文只画进图片，库里存哈希，120 秒过期且一次性。
   *
   * @param query 仅允许 {@code purpose} 一个参数
   * @return 200 与 {@code captchaId}/{@code imageBase64}/{@code expiresIn}
   */
  @GetMapping("/auth/captchas")
  public ResponseEntity<Object> captcha(
      @RequestParam Map<String, Object> query, HttpServletRequest req) {
    actor(req);
    return ok(
        200,
        auth.captcha(
            new Input(query, "purpose").choice("purpose", "sms,admin_login,password_login"), req.getRemoteAddr()));
  }

  /**
   * 登录能力探测（公开）：告诉登录页当前哪些第三方入口真正可用。
   *
   * <p>登录页只展示当前可用入口；配置存在不代表第三方已验收，因此这里返回的是
   * "配置完整且回调地址合法"的结果，而不是简单的密钥非空判断。
   *
   * <p>无鉴权、无副作用；不暴露 appId、密钥或回调地址本身。
   *
   * @return 200 与 {@code wechatLogin}/{@code wechatQrLogin}/{@code wechatMiniQrLogin} 三个布尔
   */
  @GetMapping("/auth/capabilities")
  public ResponseEntity<Object> capabilities() {
    return ok(200, map("wechatLogin", wechatLogin.configured(), "wechatQrLogin", wechatLogin.qrConfigured(), "wechatMiniQrLogin", miniQr.configured()));
  }

  /**
   * 发送登录类短信验证码。
   *
   * <p>POST {@code /auth/sms-codes}。{@code purpose} 只接受 {@code login}（免密登录/自动注册）、
   * {@code wechat_bind}（微信绑定手机号，必须带有效 {@code bindTicket}）、
   * {@code password_reset}（重置密码）。所有用途都强制携带图形验证码字段。
   *
   * <p>鉴权：可选身份校验（actor 未使用），未登录可调用。限流三层：同号 60 秒 1 条、
   * 同号每天 10 条、同 IP 每天 50 条，超限 429。
   *
   * <p>非幂等：每次成功都会产生新的 {@code smsRequestId}；{@code status} 区分
   * {@code simulated}（本地模式）与 {@code accepted}（仅代表平台已受理）。
   *
   * @return 200 与 {@code smsRequestId}/{@code expiresIn}/{@code retryAfter}/{@code phoneMasked}/{@code status}
   */
  @PostMapping("/auth/sms-codes")
  public ResponseEntity<Object> sms(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    actor(req);
    return ok(200, auth.sendLoginSms(b, req.getRemoteAddr()));
  }

  /**
   * 手机号 + 短信验证码登录（账号不存在则自动注册为买家）。
   *
   * <p>POST {@code /auth/sms-login}，字段 {@code phone/smsRequestId/smsCode}。验证码必须匹配
   * 手机号、用途（login）、作用域与哈希，且只能消费一次；同一 smsRequestId 5 分钟内最多试 5 次。
   *
   * <p>鉴权：可选身份校验，未登录可调用。写入用户记录时持全局锁，避免并发注册出两条同号账号。
   *
   * <p>非幂等：每次成功都签发新会话（买家 2 小时）。验证码错误 400，过期 400
   * {@code SMS_CODE_EXPIRED}。
   *
   * @return 200 与 {@code accessToken}、{@code expiresIn} 及 {@code user} 资料
   */
  @PostMapping("/auth/sms-login")
  public ResponseEntity<Object> login(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    actor(req);
    return ok(200, auth.smsLogin(b));
  }

  /**
   * 买家密码登录（手机号或用户名 + 图形验证码）。
   *
   * <p>密码入口统一经过服务端身份验证、限流和持久化冷却检查：IP 每分钟 30 次、
   * 同一账号失败 5 次锁 10 分钟、账号不存在时也按 {@code hash(account)} 计数，
   * 使攻击者无法通过限流行为判断手机号是否注册。
   *
   * <p>鉴权：公开。输入统一转小写（用户名不区分大小写），密码用 PBKDF2 恒定时间比对；
   * 任何失败都返回同一条 401 提示，不区分"账号不存在"与"密码错"。
   *
   * <p>非幂等（每次签发新会话）。成功清零失败计数。
   *
   * @param b 字段 {@code account/password} 与图形验证码字段
   * @return 200 与买家会话及资料
   */
  @PostMapping("/auth/password-login")
  public ResponseEntity<Object> passwordLogin(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    return ok(200, auth.passwordLogin(b, req.getRemoteAddr()));
  }

  /**
   * 忘记密码：手机号 + 专用短信验证码重置密码。
   *
   * <p>POST {@code /auth/password-reset}，字段 {@code phone/smsRequestId/smsCode/newPassword}。
   * 只接受 {@code purpose=password_reset} 的验证码——登录验证码不能拿来改密。
   *
   * <p>鉴权：公开（此时用户已无法登录）。新密码 12~128 位且须同时含字母与数字；
   * 与原密码相同 400 {@code PASSWORD_UNCHANGED}；7 天内已改过 429 {@code CHANGE_COOLDOWN}。
   *
   * <p>成功后撤销该账号全部会话——改密的核心目的就是踢掉可能被盗用的登录态；
   * 因此调用方拿到 200 后必须重新登录。
   *
   * @return 200 与提示文案，不含新会话
   */
  @PostMapping("/auth/password-reset")
  public ResponseEntity<Object> resetPassword(@RequestBody Map<String, Object> b) {
    return ok(200, auth.resetPassword(b));
  }

  /**
   * 买家修改密码（需要原密码）。
   *
   * <p>PUT {@code /me/password}。权限：{@code AuthService.role(actor, "buyer")}——管理端主体
   * 误用此入口会 403，必须走 {@code /admin/auth/password}。
   *
   * <p>三道闸：7 天冷却、原密码错误 5 次锁 10 分钟、新密码不能与原密码相同。
   * 写操作在持锁后再次 {@code requireActiveSession}，防止等待锁期间会话已被撤销（TOCTOU）。
   *
   * <p>非幂等；成功后撤销该账号全部会话。
   *
   * @return 200 与提示文案
   */
  @PutMapping("/me/password")
  public ResponseEntity<Object> changePassword(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    return ok(200, auth.changePassword(actor(req), b, "buyer"));
  }

  /**
   * 买家修改用户名。
   *
   * <p>PUT {@code /me/username}。权限：仅买家（Service 内 {@code role(actor, "buyer")}）。
   * 用户名 3~32 位、字母开头、只含小写字母/数字/下划线，统一转小写。
   *
   * <p>判定顺序刻意是"未改变 400 → 冷却 429 → 占用 409"，这样把名字改成当前值时
   * 得到的是"未改变"，而不是容易误解的"3 天后再试"。
   *
   * <p>非幂等：成功后写 {@code usernameChangedAt}，3 天内不可再改。
   *
   * @return 200 与最新的买家资料投影
   */
  @PutMapping("/me/username")
  public ResponseEntity<Object> changeUsername(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    return ok(200, auth.changeUsername(actor(req), b));
  }

  /**
   * 管理端"账号安全"页数据。
   *
   * <p>GET {@code /admin/auth/security}。权限：{@code AuthService.staff(...)}——店长与员工都可
   * 查看自己的账号信息（员工也需要改密码），区别于内容管理类接口的严格 admin。
   *
   * <p>只返回用户名、角色、显示名与下次可改密时间，不含任何凭据字段；只读、可重复调用。
   *
   * @return 200 与账号安全投影
   */
  @GetMapping("/admin/auth/security")
  public ResponseEntity<Object> security(HttpServletRequest req) {
    return ok(200, auth.adminSecurity(actor(req)));
  }

  /**
   * 管理端修改密码（店长与员工共用）。
   *
   * <p>PUT {@code /admin/auth/password}。注意传 {@code "admin"} 只是选择"管理端账号域"，
   * Service 内对 admin 分支实际调用的是 {@code staff(...)}，因此员工也能改自己的密码；
   * 仅店长可重置他人密码的入口在 {@code /admin/employees/{id}/password}。
   *
   * <p>同样受 7 天冷却、原密码 5 次失败锁定约束；成功撤销该账号全部会话（含当前会话）。
   *
   * @return 200 与提示文案
   */
  @PutMapping("/admin/auth/password")
  public ResponseEntity<Object> adminPassword(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    return ok(200, auth.changePassword(actor(req), b, "admin"));
  }

  /**
   * 后台账号密码登录（店长/员工）。
   *
   * <p>POST {@code /admin/auth/login}，字段 {@code username/password}。鉴权：公开入口，
   * 但 {@code actor(req)} 会先校验"若已带旧 token 则必须有效"，避免带着过期凭证重复登录。
   *
   * <p>刻意不使用图形验证码（后台是固定人员，每次都点码体验太差），改用 IP 每分钟 30 次 +
   * 同一账号失败 5 次锁 10 分钟。账号不存在、已停用、角色非法、密码错共用同一条 401 提示。
   *
   * <p>非幂等：成功后签发 30 分钟会话（管理端比买家短，因为它能改价、发货、退款）。
   *
   * @return 200 与 {@code accessToken}、{@code admin} 子对象（含 role/displayName）
   */
  @PostMapping("/admin/auth/login")
  public ResponseEntity<Object> adminLogin(
      @RequestBody Map<String, Object> b, HttpServletRequest req) {
    actor(req);
    return ok(200, auth.adminLogin(b, req.getRemoteAddr()));
  }

  /**
   * 退出登录（买家与管理端共用，按 URI 前缀分流）。
   *
   * <p>POST {@code /auth/logout} 或 {@code /admin/auth/logout}。权限：带 {@code /admin/} 的
   * 路径要求 {@code staff(...)}（店长+员工），其余要求 {@code role(a, "buyer")}；但两者都只
   * 在令牌有效时才校验——没有令牌（{@code a == null}）直接走"无事可做"的成功分支。
   *
   * <p><b>幂等</b>：这是本类唯一刻意吞掉 401 的接口。令牌已过期或已撤销时返回 200，
   * 因为"退出"的目标状态已经达成，让客户端看到错误只会阻止它清理本地凭证。
   *
   * <p>只撤销当前会话，不影响该账号在其它设备上的登录。
   *
   * @return 始终 200，body 为 null
   */
  @PostMapping({"/auth/logout", "/admin/auth/logout"})
  public ResponseEntity<Object> logout(HttpServletRequest req) {
    AuthService.Actor a;
    try {
      a = actor(req);
    } catch (ApiException e) {
      if (e.status == 401) return ok(200, null);
      throw e;
    }
    if (a != null) {
      if (req.getRequestURI().contains("/admin/")) AuthService.staff(a);
      else AuthService.role(a, "buyer");
    }
    auth.logout(a);
    return ok(200, null);
  }

  /**
   * 获取微信授权跳转地址（公众号网页授权 / 网站扫码登录）。
   *
   * <p>GET 或 POST {@code /auth/wechat/authorize-url}。参数 {@code mode=oauth|web}、
   * {@code scene=login|pay}、{@code returnPath}；登录场景必须通过图形验证码，
   * 支付场景要求买家身份（{@code role(actor, "buyer")}），其余情况允许匿名。
   *
   * <p>同时下发 {@code paw_oauth} Cookie：HttpOnly + Secure + SameSite=Lax、
   * 作用域限定到 {@code /api/v1/auth/wechat}、5 分钟过期。它是一次性 state 与浏览器的绑定凭据，
   * 回调必须带着同一个 Cookie 才能完成登录，防止"把授权链接发给别人代扫"。
   *
   * <p>非幂等（每次生成新 state，旧的 5 分钟后失效）；未配置时 503。
   * {@code returnPath} 只允许站内相对路径。
   *
   * @return 200 与 {@code authorizeUrl}/{@code expiresIn}
   */
  @RequestMapping(value = "/auth/wechat/authorize-url", method = {RequestMethod.GET, RequestMethod.POST})
  public ResponseEntity<Object> authorize(
      @RequestParam Map<String, Object> q, @RequestBody(required = false) Map<String, Object> b,
      HttpServletRequest req, HttpServletResponse res) {
    AuthService.Actor a = actor(req);
    String cookie = auth.randomToken();
    Map<String, Object> result = wechatLogin.authorize(b == null ? q : b, a, cookie);
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

  /**
   * 微信授权回调换取会话。
   *
   * <p>POST {@code /auth/wechat/login}，字段 {@code code/state}，并从 {@code paw_oauth} Cookie
   * 取浏览器凭据。state 是一次性的（{@code temp.take}），必须与 Cookie 的哈希匹配，
   * 否则 400 {@code WECHAT_AUTH_INVALID}——这道绑定是防 CSRF/授权码劫持的关键。
   *
   * <p>凭据类型与应用归属取自服务端保存的 state，不能由回调请求切换；
   * 支付场景（{@code scene=pay}）要求当前已是买家账号，登录场景则新建或复用微信账号。
   *
   * <p>幂等性：同一 state 只能成功一次，重放返回 400，客户端需重新发起授权。
   *
   * @param browser Cookie 中的浏览器凭据，缺失或过期都会失败
   * @return 200 与买家会话；需要绑定手机号时返回绑定票据而非会话
   */
  @PostMapping("/auth/wechat/login")
  public ResponseEntity<Object> wechat(
      @RequestBody Map<String, Object> b,
      @CookieValue(value = "paw_oauth", required = false) String browser,
      HttpServletRequest req) {
    return ok(200, wechatLogin.login(b, actor(req), browser));
  }

  /**
   * 微信首次登录时绑定手机号并换取会话。
   *
   * <p>POST {@code /auth/wechat/bind-phone}，字段 {@code bindTicket/phone/smsRequestId/smsCode}。
   * {@code bindTicket} 由 {@code /auth/wechat/login} 在 {@code status=bind_required} 时下发，
   * 5 分钟内有效且只能使用一次；验证码的场景（scope）也被绑定到该 ticket，
   * 因此不能拿别处的验证码来绑定手机号。
   *
   * <p>鉴权：可选身份校验（未使用返回值）——此时用户还没有会话，必须允许匿名进入。
   * 手机号已存在则复用该买家账号，不存在则新建；随后签发 2 小时买家会话。
   *
   * <p>非幂等：ticket 被消费后重放返回 400 {@code WECHAT_AUTH_INVALID}。
   *
   * @return 200 与买家会话；微信已绑定其他账号时 409 {@code WECHAT_ALREADY_BOUND}
   */
  @PostMapping("/auth/wechat/bind-phone")
  public ResponseEntity<Object> bind(@RequestBody Map<String, Object> b, HttpServletRequest req) {
    actor(req);
    return ok(200, wechatLogin.bindPhone(b));
  }
}
