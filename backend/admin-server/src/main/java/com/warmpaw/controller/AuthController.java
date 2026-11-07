package com.warmpaw.controller;

import static com.warmpaw.common.Json.*;
import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.common.ApiException;
import com.warmpaw.common.Input;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.GraphCaptchaService;
import com.warmpaw.service.GuestService;
import com.warmpaw.web.BuyerSessionCookie;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * 账号登录与验证码接口（调用方：买家、店长/员工、第三方 AI 服务）。
 *
 * <p>路由前缀 {@code /api/v1}，本类同时承载买家端 {@code /auth/**}、{@code /me/**} 与
 * 管理端 {@code /admin/auth/**} 三组入口。
 *
 * <p><b>权限模型：没有统一的拦截器</b>，每个方法自行决定：
 *
 * <ul>
 *   <li>登录类入口（sms-login、password-login、admin-login、password-reset、graph-captcha）完全公开，靠图形验证码、短信验证码与失败计数防刷；
 *   <li>其余方法先调用 {@link #actor} 解析身份。{@code AuthService.identify} 在没有
 *       {@code Authorization} 头时返回 {@code null}（匿名合法），所以"调用了 actor(req) 却
 *       不使用返回值"只表示"若带了 token 就必须有效"，并不代表强制登录；
 *   <li>真正要求登录/角色的地方在 Service 层：{@code AuthService.role(...)} 用于买家或店长，
 *       {@code AuthService.staff(...)} 用于店长+员工。
 * </ul>
 *
 * <p>委托：{@link GuestService}（游客身份）、{@link AuthService}（会话与账号安全）、
 * {@link GraphCaptchaService}（图形验证码）。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final GuestService guests;
  private final AuthService auth;
  private final GraphCaptchaService graphCaptcha;
  private final BuyerSessionCookie cookie;

  public AuthController(
      GuestService guests, AuthService auth, GraphCaptchaService graphCaptcha, BuyerSessionCookie cookie) {
    this.guests = guests;
    this.auth = auth;
    this.graphCaptcha = graphCaptcha;
    this.cookie = cookie;
  }

  /**
   * 买家网站登录成功：token 只写进 HttpOnly Cookie，响应体去掉 {@code accessToken}，前端 JS 拿不到。
   * 小程序走 {@code /auth/mini-wechat/login}，仍在响应体里返回 Bearer token。
   */
  private ResponseEntity<Object> webLogin(Map<String, Object> session, HttpServletResponse res) {
    Map<String, Object> body = new LinkedHashMap<>(session);
    String token = (String) body.remove("accessToken");
    body.remove("tokenType");
    res.addHeader(HttpHeaders.SET_COOKIE, cookie.issue(token, ((Number) body.get("expiresIn")).longValue()));
    return ok(200, body);
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
   * 发送登录类短信验证码。
   *
   * <p>POST {@code /auth/sms-codes}。{@code purpose} 只接受 {@code login}（免密登录/自动注册）、
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
   * <p>非幂等：每次成功都签发新会话（买家 24 小时，token 写入 HttpOnly Cookie）。验证码错误 400，过期 400
   * {@code SMS_CODE_EXPIRED}。
   *
   * @return 200 与 {@code expiresIn} 及 {@code user} 资料；token 只在 {@code Set-Cookie} 里
   */
  @PostMapping("/auth/sms-login")
  public ResponseEntity<Object> login(
      @RequestBody Map<String, Object> b, HttpServletRequest req, HttpServletResponse res) {
    actor(req);
    return webLogin(auth.smsLogin(b), res);
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
   * @return 200 与买家会话有效期及资料；token 只在 {@code Set-Cookie} 里
   */
  @PostMapping("/auth/password-login")
  public ResponseEntity<Object> passwordLogin(
      @RequestBody Map<String, Object> b, HttpServletRequest req, HttpServletResponse res) {
    return webLogin(auth.passwordLogin(b, req.getRemoteAddr()), res);
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
  public ResponseEntity<Object> changePassword(
      @RequestBody Map<String, Object> b, HttpServletRequest req, HttpServletResponse res) {
    Map<String, Object> result = auth.changePassword(actor(req), b, "buyer");
    // 改密已撤销全部会话，本浏览器的登录 Cookie 同步删除。
    res.addHeader(HttpHeaders.SET_COOKIE, cookie.clear());
    return ok(200, result);
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
   * <p>只撤销当前会话，不影响该账号在其它设备上的登录。买家退出时总是下发删除 Cookie，
   * 即使会话早已失效，也能让浏览器清掉残留的登录 Cookie。
   *
   * @return 始终 200，body 为 null
   */
  @PostMapping({"/auth/logout", "/admin/auth/logout"})
  public ResponseEntity<Object> logout(HttpServletRequest req, HttpServletResponse res) {
    if (!req.getRequestURI().contains("/admin/")) res.addHeader(HttpHeaders.SET_COOKIE, cookie.clear());
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
}
