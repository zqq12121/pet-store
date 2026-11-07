package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证与账号安全：短信登录、密码登录、不透明会话token、验证码、改密/改用户名。
 *
 * <p><b>会话模型</b>：不签发 JWT，而是随机 32 字节 token，库里只存它的 SHA-256 摘要
 * （{@code session} 表按摘要做键）。好处是数据库泄露也拿不到可用 token，且退出登录可以真正撤销
 * ——JWT 的痛点正是"签出去就撤不回"，除非再维护一张黑名单表，那还不如直接存会话。
 *
 * <p>会话有效期：管理端 30 分钟、买家网站 24 小时（靠 HttpOnly Cookie 保持，见 {@code BuyerSessionCookie}）、
 * 小程序 2 小时（{@link #MINI_PROGRAM_SECONDS}）。
 * 管理端更短是因为它能改价格、发货、退款。
 *
 * <p><b>{@link #identify} 返回 null 而不是抛异常</b>：公开接口（宠物列表、门店信息）允许匿名访问，
 * 但登录用户能看到更多（比如收藏状态）。所以"没有 Authorization 头"是合法的匿名，
 * 而"带了头但无效"是错误，必须 401。这个区分很关键，前端据此决定是清 token 还是静默降级。
 *
 * <p><b>验证码不进响应也不进日志</b>：短信验证码只以哈希形式存 {@link TemporaryStore}（5 分钟），
 * 图像验证码的明文只画进 PNG。开发模式下额外写 {@code data/local-inbox}，仅本机可读（0600）。
 *
 * <p><b>限流分层</b>：IP 维度防刷接口、手机号维度防轰炸、账号维度防撞库、单请求维度防爆破验证码。
 * 四层分别有独立窗口，见各方法内的 {@code temp.limit}/{@code temp.checkFailures}。
 */
@Service
public class AuthService {
  /**
   * 当前请求的认证主体。
   *
   * @param id    账号 id（买家是 {@code user} 表主键，管理端是 {@code admin} 表主键）
   * @param role  {@code buyer} / {@code staff} / {@code admin}
   * @param phone 账号绑定手机号，可能为 null（纯微信账号）
   * @param tokenHash 当前 token 的摘要，用于退出登录、改密后撤销会话
   */
  public record Actor(String id, String role, String phone, String tokenHash) {}

  private final BusinessRepository store;
  private final TemporaryStore temp;
  private final boolean mock;
  private final SmsGateway smsGateway;
  private final GraphCaptchaService graphCaptcha;
  /** 密码哈希与校验已拆到独立职责，见 {@link PasswordService} 的类注释。 */
  private final PasswordService passwords;
  private final SecureRandom random = new SecureRandom();

  public AuthService(
      BusinessRepository store,
      TemporaryStore temp,
      SmsGateway smsGateway,
      GraphCaptchaService graphCaptcha,
      PasswordService passwords,
      @Value("${app.mock-providers}") boolean mock) {
    this.store = store;
    this.temp = temp;
    this.mock = mock;
    this.smsGateway = smsGateway;
    this.graphCaptcha = graphCaptcha;
    this.passwords = passwords;
  }

  /**
   * 是否为本地/测试模式。
   *
   * <p>这个标志控制三件事：短信是否真的发送、验证码是否写本地收件箱、
   * 以及支付/模拟接口是否开放。业务代码用它做分支时要谨慎——它同时是安全开关。
   */
  public boolean local() {
    return mock;
  }

  /** 生成 32 字节 URL-safe 随机 token（无填充），用于会话与自提二维码 payload。 */
  public String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** 生成指定位数的纯数字随机串（验证码、自提码）。使用 {@link SecureRandom}，不可预测。 */
  public String digits(int length) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < length; i++) s.append(random.nextInt(10));
    return s.toString();
  }

  /**
   * 手机号掩码：{@code 138****8000}。
   *
   * <p>微信账号可以没有手机号，此时返回 null 而不是补一个虚构号码——
   * 前端据此显示"未绑定"，而不是展示一个错误的号码诱导用户去改。
   */
  public static String mask(String phone) {
    // 微信账号可以没有手机号，个人资料不生成虚构号码。
    if (phone == null || phone.isBlank()) return null;
    return phone.substring(0, 3) + "****" + phone.substring(7);
  }

  /**
   * 从 Authorization 头解析当前用户。
   *
   * <p>四个校验按顺序：
   *
   * <ol>
   *   <li>头为 null → 返回 null（匿名，合法）；
   *   <li>不是 {@code Bearer } 开头 → 401（格式错的凭证不该被当匿名）；
   *   <li>会话不存在或状态非 active → 401（已退出、被撤销）；
   *   <li>会话过期 → 401 {@code TOKEN_EXPIRED}（单独错误码让前端能提示"登录已过期"）；
   * </ol>
   *
   * <p>管理端额外做一次"实时权限复核"：员工被停用或角色被降级后，已签发的会话必须立即失效。
   * 否则一个被开除的员工还能用旧 token 操作 30 分钟。
   *
   * <p>注意库里存的是 token 摘要，查会话用 {@code hash(bearer)}，原文不落库。
   */
  public Actor identify(String bearer) {
    if (bearer == null) return null;
    require(bearer.startsWith("Bearer "), 401, "UNAUTHORIZED", "登录凭证无效");
    String digest = hash(bearer.substring(7));
    Map<String, Object> session = store.byKey("session", digest);
    require(
        session != null && "active".equals(text(session, "status")), 401, "UNAUTHORIZED", "请先登录");
    require(
        Instant.parse(text(session, "expiresAt")).isAfter(Instant.now()),
        401,
        "TOKEN_EXPIRED",
        "登录已过期");
    String user = text(session, "ownerId");
    Map<String, Object> account =
        store.get(List.of("admin", "staff").contains(text(session, "role")) ? "admin" : "user", user);
    // 员工停用或角色改变后，已有会话不能继续使用。
    if (List.of("admin", "staff").contains(text(session, "role")))
      require(!"disabled".equals(text(account, "status"))
          && text(session, "role").equals(accountRole(account)), 401, "UNAUTHORIZED", "请重新登录");
    return new Actor(user, text(session, "role"), text(account, "phone"), digest);
  }

  /**
   * 要求主体具备指定角色，否则 403。
   *
   * <p>未登录是 401（该去登录），登录了但角色不对是 403（登录了也没用）——这个区分让前端能正确引导。
   */
  public static void role(Actor actor, String role) {
    require(actor != null, 401, "UNAUTHORIZED", "请先登录");
    require(role.equals(actor.role()), 403, "FORBIDDEN", "无操作权限");
  }

  /**
   * 要求主体是"店内工作人员"（店长或员工任一）。
   *
   * <p>这是接待岗和店长共用的后台入口：日常核销、查单用这个；
   * 涉及定价、上下架、退款审批的接口仍然要求 {@link #role} 严格等于 {@code admin}。
   */
  public static void staff(Actor actor) {
    require(actor != null, 401, "UNAUTHORIZED", "请先登录");
    require(List.of("admin", "staff").contains(actor.role()), 403, "FORBIDDEN", "无操作权限");
  }

  /** 取账号角色，缺省视为 {@code admin}（历史数据没有 role 字段时按店长处理，避免员工被误判为无权限）。 */
  private String accountRole(Map<String, Object> account) {
    return Objects.toString(account.get("role"), "admin");
  }

  /**
   * 发送登录类短信验证码。
   *
   * <p>两种用途：
   *
   * <ul>
   *   <li>{@code login} —— 手机号免密登录（不存在则自动注册）；
   *   <li>{@code password_reset} —— 重置密码。
   * </ul>
   *
   * <p>所有用途都先过图形验证码（{@code GraphCaptchaService.FIELDS}），
   * 这是防"短信轰炸"的第一道闸——只靠手机号维度的频控不够，攻击者可以换号。
   */
  public Map<String, Object> sendLoginSms(Map<String, Object> body, String ip) {
    Input in = new Input(body, "phone,purpose," + GraphCaptchaService.FIELDS);
    String phone = in.phone("phone"), purpose = in.choice("purpose", "login,password_reset");
    graphCaptcha.verify(body);
    return sendSms(phone, purpose, "", ip);
  }

  /**
   * 发送短信验证码（内部统一实现）。
   *
   * <p><b>三层限流</b>：同号码 60 秒 1 条、同号码每天 10 条、同 IP 每天 50 条。
   * 三个维度缺一不可：只有号码维度挡不住换号轰炸，只有 IP 维度挡不住代理池。
   *
   * <p><b>先发后存</b>：{@code smsGateway.send} 成功后才把验证码写进临时存储。
   * 如果发送失败却已经存了码，这段窗口期内码是"有效但用户收不到"的，重试还会被限流挡住。
   *
   * <p>对外的 {@code status} 区分 {@code simulated}（本地模式）与 {@code accepted}（平台已受理）。
   * {@code accepted} 只代表短信平台收下了请求，不代表用户一定能收到——这是运营商侧的事实，
   * 接口不能承诺送达。
   */
  public Map<String, Object> sendSms(String phone, String purpose, String scope, String ip) {
    // 对外服务未配置时失败关闭，不将模拟发送当作真实短信。

    temp.limit("sms-minute:" + phone, 1, 60);
    temp.limit("sms-day:" + phone, 10, 86400);
    temp.limit("sms-ip:" + ip, 50, 86400);
    String id = id("sms"), code = digits(6);
    // 仅平台受理后保存验证码，发送失败不能留下有效登录凭据。
    if (!mock) smsGateway.send(phone, code, purpose);
    temp.put(
        "sms:" + id,
        write(map("phone", phone, "purpose", purpose, "scope", scope, "hash", hash(code))),
        300);
    devSecret("sms", id, code);
    return map("smsRequestId", id, "expiresIn", 300, "retryAfter", 60, "phoneMasked", mask(phone),
        "status", mock ? "simulated" : "accepted");
  }

  /**
   * 校验并消费短信验证码。
   *
   * <p>四个要素必须全部匹配：手机号、用途、作用域（scope）、验证码哈希。
   * scope 是防止跨场景复用的关键——登录码不能拿去确认自提，确认 A 单的码不能用于 B 单。
   *
   * <p>限流：同一 {@code smsRequestId} 5 分钟内最多试 5 次，挡住 6 位码的暴力枚举
   * （100 万种组合，5 次/5 分钟的速率下不可行）。
   *
   * <p>最后用 {@code temp.take} 原子取出并删除：并发下只有一个请求能拿到值，
   * 保证"验证码只能用一次"。这一步放在哈希比对之后，是为了让用户输错时还能重试。
   *
   * <p>{@code synchronized} 是为了让"比对 + 消费"这两步在单实例内不被打断。
   */
  public synchronized void checkSms(
      String request, String code, String phone, String purpose, String scope) {
    temp.limit("sms-attempt:" + request, 5, 300);
    String value = temp.get("sms:" + request);
    require(value != null, 400, "SMS_CODE_EXPIRED", "短信验证码已过期");
    Map<String, Object> m = read(value);
    require(
        phone.equals(text(m, "phone"))
            && purpose.equals(text(m, "purpose"))
            && scope.equals(text(m, "scope"))
            && hash(code).equals(text(m, "hash")),
        400,
        "SMS_CODE_INVALID",
        "短信验证码不正确");
    require(temp.take("sms:" + request) != null, 400, "SMS_CODE_INVALID", "验证码已使用");
  }

  /**
   * 短信验证码登录（不存在则自动注册）。
   *
   * <p>先取全局锁再查用户：两个请求同时用同一个新手机号登录时，
   * 不加锁会创建出两条 {@code user} 记录（手机号是业务键，但不一定有唯一索引兜底）。
   *
   * <p>自动注册的账号没有密码、没有用户名，只有手机号与默认昵称，
   * 后续可在个人中心设置（见 {@link #changePassword}、{@link #changeUsername}）。
   */
  @Transactional
  public Map<String, Object> smsLogin(Map<String, Object> body) {
    Input in = new Input(body, "phone,smsRequestId,smsCode");
    String phone = in.phone("phone");
    checkSms(in.str("smsRequestId", 1, 64), in.str("smsCode", 6, 6), phone, "login", "");
    store.lock();
    Map<String, Object> user = store.byKey("user", phone);
    if (user == null)
      user =
          store.create(
              "user", null, phone, map("phone", phone, "nickname", "新用户", "avatarUrl", null));
    return login(user, "buyer");
  }

  /** 买家查看自己的资料；先断言角色为 buyer，避免管理端主体误用这个入口。 */
  public Map<String, Object> profile(Actor actor) {
    role(actor, "buyer");
    return profile(store.get("user", actor.id()));
  }

  /**
   * 账号资料的对外投影。
   *
   * <p>刻意不含 {@code passwordHash}、原始手机号（只给掩码）、内部状态字段。
   * {@code hasPassword} 用布尔代替哈希，让前端能显示"未设置密码"并引导设置，
   * 同时不泄露任何凭据信息。
   *
   * <p>两个 {@code *ChangeAvailableAt} 是"下次可修改时间"，前端据此置灰按钮，
   * 而不是等用户点下去才报 429。
   */
  private Map<String, Object> profile(Map<String, Object> user) {
    return map(
        "id",
        user.get("id"),
        "username", user.get("username"),
        "hasPassword", user.get("passwordHash") != null,
        "passwordChangeAvailableAt", nextChange(user, "passwordChangedAt", 7),
        "usernameChangeAvailableAt", nextChange(user, "usernameChangedAt", 3),
        "nickname",
        user.get("nickname"),
        "avatarUrl",
        user.get("avatarUrl"),
        "phoneMasked",
        mask(text(user, "phone")),
        "createdAt",
        user.get("createdAt"));
  }

  /**
   * 签发会话，返回登录响应。
   *
   * <p>有效期按角色区分：管理端 1800 秒（30 分钟），买家 86400 秒（24 小时）。
   *
   * <p>响应结构有一个动态键：管理端用 {@code admin}，买家端用 {@code user}。
   * 这不是笔误，而是为了让同一份前端代码能按角色读不同字段，同时不把管理端的
   * {@code role}/{@code displayName} 结构暴露给买家。
   *
   * <p>库里存的是 {@code hash(token)}，返回给客户端的才是明文 token。
   */
  public Map<String, Object> login(Map<String, Object> account, String role) {
    return login(account, role, List.of("admin", "staff").contains(role) ? 1800 : 86400);
  }

  /** 小程序登录会话有效期：保持原来的 2 小时，24 小时只用于买家网站。 */
  public static final int MINI_PROGRAM_SECONDS = 7200;

  /** 按指定有效期签发会话；目前只有小程序用它覆盖默认的买家 24 小时。 */
  public Map<String, Object> login(Map<String, Object> account, String role, int seconds) {
    String token = randomToken();
    boolean management = List.of("admin", "staff").contains(role);
    store.create(
        "session",
        text(account, "id"),
        hash(token),
        map(
            "status",
            "active",
            "role",
            role,
            "expiresAt",
            Instant.now().plusSeconds(seconds).toString()));
    return map(
        "accessToken",
        token,
        "tokenType",
        "Bearer",
        "expiresIn",
        seconds,
        management ? "admin" : "user",
        management
            ? map("id", account.get("id"), "username", account.get("username"), "role", role,
                "displayName", account.get("displayName"))
            : profile(account));
  }

  /**
   * 后台账号密码登录。
   *
   * <p><b>不使用图形验证码</b>，改用"账号失败计数 + IP 频控"：后台是店内固定人员使用，
   * 每次登录都要点图形码体验太差；而失败 5 次锁 10 分钟的强度已经足够挡住撞库。
   *
   * <p>五种失败情况（账号不存在、已停用、角色不合法、密码错、账号为空）共用同一条错误信息
   * {@code "账号或密码不正确"}，并且都计入失败次数。这样攻击者无法通过错误信息区分
   * "账号存在但密码错"和"账号不存在"，也就无法枚举账号。
   *
   * <p>成功时清零失败计数。注意 {@code store.lock()} 在查账号之前——密码校验和会话创建
   * 必须与"账号是否被并发修改"基于同一份数据。
   */
  @Transactional
  public Map<String, Object> adminLogin(Map<String, Object> body, String ip) {
    new Input(body, "username,password");
    // 后台不使用人机验证，保留账号失败锁定并限制来源 IP 的请求频率。
    temp.limit("admin-login-ip:" + ip, 30, 60);
    String username = loginCredential(body, "username", 32);
    String password = loginCredential(body, "password", 128);
    temp.checkFailures("admin-login:" + hash(username), 5);
    store.lock();
    Map<String, Object> account = store.byKey("admin", username);
    if (account == null || "disabled".equals(text(account, "status"))
        || !List.of("admin", "staff").contains(accountRole(account))
        || !passwordMatches(password, text(account, "passwordHash"))) {
      temp.failed("admin-login:" + hash(username), 600);
      throw new ApiException(401, "UNAUTHORIZED", "账号或密码不正确");
    }
    temp.resetFailures("admin-login:" + hash(username));
    return login(account, accountRole(account));
  }

  /**
   * 退出登录：把会话置为 {@code revoked}。
   *
   * <p>未登录（{@code actor == null}）直接返回成功，不报错——退出操作应当幂等，
   * 客户端重试或 token 已过期时不该弹错误。
   *
   * <p>这里只撤销当前这一个会话，不影响该账号在其它设备上的登录。
   */
  @Transactional
  public void logout(Actor actor) {
    if (actor == null) return;
    Map<String, Object> session = store.byKey("session", actor.tokenHash());
    if (session != null) {
      session.put("status", "revoked");
      store.save(session);
    }
  }

  /**
   * 生成密码哈希。
   *
   * <p>实现已移到 {@link PasswordService}（PBKDF2-HMAC-SHA256，210000 轮，独立随机盐）。
   * 这里保留同名方法是为了不动调用点——{@code Bootstrap}、{@code EmployeeService}
   * 与多个测试都用 {@code auth.passwordHash(...)}。
   */
  public String passwordHash(String password) {
    return passwords.hash(password);
  }

  /**
   * 恒定时间比对密码哈希。
   *
   * <p>同样委托给 {@link PasswordService}；保留这个私有方法是因为本类内
   * 有四处校验（管理员登录、密码登录、改密、重置密码），用一个转发比改四行更清楚。
   */
  private boolean passwordMatches(String password, String encoded) {
    return passwords.matches(password, encoded);
  }

  /**
   * 读取登录凭据字段。
   *
   * <p>三条约束都返回同一个 401：非字符串、空白、超长。超长上限（用户名 32、密码 128）
   * 是为了避免 PBKDF2 被超长输入拖慢（210000 轮 × 1MB 输入会显著占用 CPU，可被用作 DoS）。
   *
   * <p>不暴露"密码太长"这类具体原因，与账号不存在共用提示，避免信息泄露。
   */
  private String loginCredential(Map<String, Object> body, String key, int maxLength) {
    Object value = body == null ? null : body.get(key);
    if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > maxLength)
      throw new ApiException(401, "UNAUTHORIZED", "账号或密码不正确");
    return text.trim();
  }

  /**
   * 买家密码登录（支持手机号或用户名 + 图形验证码）。
   *
   * <p>输入统一转小写：用户名不区分大小写，避免"Zhang"和"zhang"被当成两个账号
   * （数据库的唯一性检查是按小写做的，这里必须一致）。
   *
   * <p>手机号与用户名<b>共用同一个失败计数键</b>（按用户 id 而非输入值）。
   * 如果各算各的，攻击者可以在"手机号登录"和"用户名登录"之间交替尝试，
   * 每个通道各得 5 次机会，等于把限流放大了两倍。
   *
   * <p>账号不存在时用 {@code hash(account)} 作为计数键，这样连"这个手机号是否注册过"
   * 都无法通过限流行为推断出来。
   */
  @Transactional
  public Map<String, Object> passwordLogin(Map<String, Object> body, String ip) {
    Input in = new Input(body, "account,password," + GraphCaptchaService.FIELDS);
    String account = loginCredential(body, "account", 32).toLowerCase(Locale.ROOT);
    String password = loginCredential(body, "password", 128);
    temp.limit("password-login-ip:" + ip, 30, 60);
    graphCaptcha.verify(body);
    store.lock();
    Map<String, Object> user = account.matches("1[3-9][0-9]{9}")
        ? store.byKey("user", account) : store.userByUsername(account);
    // 手机号、用户名使用同一个账号失败计数，不能交替登录绕过限流。
    String key = "buyer-password:" + (user == null ? hash(account) : text(user, "id"));
    temp.checkFailures(key, 5);
    if (user == null || !passwordMatches(password, text(user, "passwordHash"))) {
      temp.failed(key, 600);
      throw new ApiException(401, "UNAUTHORIZED", "账号或密码不正确");
    }
    temp.resetFailures(key);
    return login(user, "buyer");
  }

  /**
   * 计算"下次可修改时间"：上次修改时间 + 冷却天数。
   *
   * <p>从未修改过（字段为空）返回 null，表示"现在就可以改"。
   * 用服务器时间与持久化时间戳计算，不信任客户端时间。
   */
  private String nextChange(Map<String, Object> account, String field, int days) {
    String changed = text(account, field);
    return changed == null || changed.isBlank() ? null : Instant.parse(changed).plus(Duration.ofDays(days)).toString();
  }

  /**
   * 冷却检查：未到时间则 429 并告知可修改时间。
   *
   * <p>密码 7 天、用户名 3 天。这两个限制的意义不是防暴力破解（那是限流的事），
   * 而是防止账号被他人短暂控制后被反复改动，以及给"找回账号"留出追溯窗口。
    *
   * <p>使用服务器时间与持久化时间戳计算；首次设置（字段为空）允许执行，之后按滚动天数限制。
   */
  private void checkCooldown(Map<String, Object> account, String field, int days) {
    String next = nextChange(account, field, days);
    require(next == null || !Instant.parse(next).isAfter(Instant.now()), 429,
        "CHANGE_COOLDOWN", "每" + days + "天只能修改一次，下次可修改时间：" + next);
  }

  /**
   * 校验并返回新密码。
   *
   * <p>规则：12~128 位，必须同时含字母和数字。
   *
   * <p>不强制大小写/符号，是权衡后的选择：过严的规则会促使用户写下密码或复用密码，
   * 12 位下限配合 210000 轮 PBKDF2 已经足够。
   */
  private String newPassword(Input in) {
    String password = in.str("newPassword", 12, 128);
    require(password.matches(".*[a-zA-Z].*") && password.matches(".*[0-9].*"),
        400, "VALIDATION_ERROR", "密码须为12至128位，包含字母和数字");
    return password;
  }

  /**
   * 保存新密码并撤销该账号的全部会话。
   *
   * <p>四步缺一不可：
   *
   * <ol>
   *   <li>冷却检查（7 天）；
   *   <li>拒绝与原密码相同（否则用户以为改了，实际没变）；
   *   <li>写入新哈希与时间戳；
   *   <li><b>撤销所有会话</b>——改密码的核心目的就是踢掉可能被盗用的登录态。
   * </ol>
   *
   * <p>{@code role} 参数用于定位要撤销的是 {@code user} 还是 {@code admin} 域的会话。
   */
  private void savePassword(Map<String, Object> account, String password, String role) {
    checkCooldown(account, "passwordChangedAt", 7);
    require(!passwordMatches(password, text(account, "passwordHash")), 400,
        "PASSWORD_UNCHANGED", "新密码不能与原密码相同");
    account.put("passwordHash", passwordHash(password));
    account.put("passwordChangedAt", Instant.now().toString());
    store.save(account);
    store.revokeSessions(text(account, "id"), role);
    store.audit(text(account, "id"), "password_changed", text(account, "id"));
  }

  /**
   * 通过手机号 + 短信验证码重置密码（忘记密码场景）。
   *
   * <p>只接受 {@code purpose=password_reset} 的验证码：登录验证码不能拿来重置密码，
   * 否则"用短信登录过一次"就等于获得了改密权限。
   *
   * <p>账号不存在时返回 400 {@code ACCOUNT_NOT_REGISTERED} 而不是静默成功——
   * 这里确实会暴露"该手机号未注册"，但重置密码必须给出可理解的反馈，
   * 否则用户会反复尝试并以为系统坏了。风险由短信验证码本身（只有号主能收到）承担。
   *
   * <p>重置与首次设置都要求 {@code password_reset} 专用验证码，不接受登录验证码。
   */
  @Transactional
  public Map<String, Object> resetPassword(Map<String, Object> body) {
    Input in = new Input(body, "phone,smsRequestId,smsCode,newPassword");
    String phone = in.phone("phone"), password = newPassword(in);
    checkSms(in.str("smsRequestId", 1, 64), in.str("smsCode", 6, 6), phone, "password_reset", "");
    store.lock();
    Map<String, Object> user = store.byKey("user", phone);
    require(user != null, 400, "ACCOUNT_NOT_REGISTERED", "请先使用短信登录创建账号");
    savePassword(user, password, "buyer");
    return map("message", "密码已更新，请重新登录");
  }

  /**
   * 已登录用户修改密码（需要原密码）。
   *
   * <p>买家与店长共用这个方法，靠 {@code role} 参数区分：传 {@code admin} 时要求
   * 主体是店长或员工，其余情况要求角色严格匹配。
   *
   * <p>关键在 {@code store.lock()} 之后调 {@link #requireActiveSession}：
   * 拿到锁后再确认会话仍然有效，防止"等待锁的这段时间里，另一个请求已经把会话撤销了"。
   * 这是典型的 TOCTOU 防护。
   *
   * <p>原密码错误 5 次锁 10 分钟，且与其它密码操作分开计数。
   */
  @Transactional
  public Map<String, Object> changePassword(Actor actor, Map<String, Object> body, String role) {
    if (role.equals("admin")) staff(actor);
    else role(actor, role);
    Input in = new Input(body, "oldPassword,newPassword");
    String password = newPassword(in);
    store.lock();
    requireActiveSession(actor);
    Map<String, Object> account = store.get(role.equals("admin") ? "admin" : "user", actor.id());
    String key = "password-change:" + actor.id();
    temp.checkFailures(key, 5);
    if (!passwordMatches(in.str("oldPassword", 8, 128), text(account, "passwordHash"))) {
      temp.failed(key, 600);
      throw new ApiException(400, "PASSWORD_INVALID", "原密码不正确；尚未设置密码请使用手机号验证设置");
    }
    savePassword(account, password, actor.role());
    temp.resetFailures(key);
    return map("message", "密码已更新，请重新登录");
  }

  /**
   * 写操作持锁后的二次认证。
   *
   * <p>为什么需要再查一次：{@code identify} 在请求进入时执行，之后可能等了很久才拿到锁。
   * 这期间会话可能已过期、被"退出登录"撤销、或员工已被停用。
   * 不复查就会出现"已退出登录的请求仍然改掉了密码"。
   */
  public void requireActiveSession(Actor actor) {
    Map<String, Object> session = store.byKey("session", actor.tokenHash());
    require(session != null && "active".equals(text(session, "status"))
        && Instant.parse(text(session, "expiresAt")).isAfter(Instant.now()),
        401, "UNAUTHORIZED", "请重新登录");
    if (List.of("admin", "staff").contains(actor.role())) {
      Map<String, Object> account = store.get("admin", actor.id());
      require(!"disabled".equals(text(account, "status"))
          && actor.role().equals(accountRole(account)), 401, "UNAUTHORIZED", "请重新登录");
    }
  }

  /**
   * 修改用户名。
   *
   * <p>规则：3~32 位，必须字母开头，只允许小写字母/数字/下划线；统一转小写。
   *
   * <p>顺序上先做"是否与原值相同"（400）再做冷却检查（429），最后查占用（409）——
   * 这样用户把用户名改成自己现在的名字时，得到的是"未改变"而不是"3 天后再试"。
   *
   * <p>冷却 3 天比密码的 7 天短，因为用户名不像密码那样直接关系账号安全，
   * 但它影响他人能否找到你（社交属性），所以仍需要限制。
   */
  @Transactional
  public Map<String, Object> changeUsername(Actor actor, Map<String, Object> body) {
    role(actor, "buyer");
    String username = new Input(body, "username").str("username", 3, 32).toLowerCase(Locale.ROOT);
    require(username.matches("[a-z][a-z0-9_]{2,31}"), 400, "VALIDATION_ERROR",
        "用户名须以字母开头，包含3至32位字母、数字或下划线");
    store.lock();
    requireActiveSession(actor);
    Map<String, Object> account = store.get("user", actor.id());
    require(!username.equals(text(account, "username")), 400, "USERNAME_UNCHANGED", "用户名未改变");
    checkCooldown(account, "usernameChangedAt", 3);
    require(store.userByUsername(username) == null, 409, "USERNAME_TAKEN", "用户名已被使用");
    account.put("username", username);
    account.put("usernameChangedAt", Instant.now().toString());
    store.save(account);
    return profile(account);
  }

  /** 管理端"账号安全"页：用户名、角色、显示名、下次可改密时间。不含任何凭据字段。 */
  public Map<String, Object> adminSecurity(Actor actor) {
    staff(actor);
    Map<String, Object> account = store.get("admin", actor.id());
    return map("username", account.get("username"), "role", accountRole(account),
        "displayName", account.get("displayName"),
        "passwordChangeAvailableAt", nextChange(account, "passwordChangedAt", 7));
  }

  /**
   * 店长重置员工密码。
   *
   * <p>复用 {@link #savePassword}，所以员工同样受 7 天冷却约束——
   * 防止店长频繁重置导致员工无法建立自己的密码习惯，也避免"重置"变成一种静默监控手段。
   *
   * <p>重置后撤销该员工全部会话（{@code savePassword} 内完成），员工必须用新密码重新登录。
   */
  public void resetStaffPassword(Map<String, Object> account, Map<String, Object> body) {
    savePassword(account, newPassword(new Input(body, "newPassword,version")), "staff");
  }

  /**
   * 把预约通知写进本地开发收件箱。
   *
   * <p>只在本地模式可用；线上模式返回 503。这不是"降级"，而是明确拒绝——
   * 生产环境绝不能有"把用户短信内容写到某个文件里"的代码路径。
   */
  public void localAppointmentNotice(String id, Map<String, Object> notice) {
    require(mock, 503, "SERVICE_UNAVAILABLE", "真实模式不可写入开发短信收件箱");
    devSecret("sms-appointment", id, Json.write(notice));
  }

  /**
   * 本地开发收件箱：把验证码/通知明文写到 {@code data/local-inbox/<kind>-<id>.txt}。
   *
   * <p>三重保护：只在 {@code mock} 模式写、目录权限 {@code rwx------}、文件权限 {@code rw-------}。
   * 这样即使开发机上还有别的用户，也读不到验证码。
   *
   * <p>文件名用系统生成的 id，不拼用户输入，杜绝路径穿越。
   */
  private void devSecret(String kind, String id, String code) {
    if (!mock) return;
    try {
      Path dir = Path.of("data/local-inbox");
      Files.createDirectories(dir);
      Files.setPosixFilePermissions(
          dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      Path file = dir.resolve(kind + "-" + id + ".txt");
      Files.writeString(file, code);
      Files.setPosixFilePermissions(
          file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (Exception e) {
      throw new IllegalStateException("无法写入本地开发收件箱", e);
    }
  }
}
