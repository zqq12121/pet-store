package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 短信登录、密码登录和可撤销的不透明会话；验证码不进入正常响应或日志。 */
@Service
public class AuthService {
  public record Actor(String id, String role, String phone, String tokenHash) {}

  private final BusinessRepository store;
  private final TemporaryStore temp;
  private final boolean mock;
  private final SmsGateway smsGateway;
  private final SecureRandom random = new SecureRandom();

  public AuthService(
      BusinessRepository store,
      TemporaryStore temp,
      SmsGateway smsGateway,
      @Value("${app.mock-providers}") boolean mock) {
    this.store = store;
    this.temp = temp;
    this.mock = mock;
    this.smsGateway = smsGateway;
  }

  public boolean local() {
    return mock;
  }

  public String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String digits(int length) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < length; i++) s.append(random.nextInt(10));
    return s.toString();
  }

  public static String mask(String phone) {
    return phone.substring(0, 3) + "****" + phone.substring(7);
  }

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
        store.get("admin".equals(text(session, "role")) ? "admin" : "user", user);
    return new Actor(user, text(session, "role"), text(account, "phone"), digest);
  }

  public static void role(Actor actor, String role) {
    require(actor != null, 401, "UNAUTHORIZED", "请先登录");
    require(role.equals(actor.role()), 403, "FORBIDDEN", "无操作权限");
  }

  public Map<String, Object> captcha(String purpose, String ip) {
    require(List.of("sms", "admin_login", "password_login").contains(purpose), 400, "VALIDATION_ERROR", "验证码用途不正确");
    temp.limit("captcha:" + ip, 30, 60);
    String id = id("captcha"), code = digits(4);
    temp.put("captcha:" + id, write(map("purpose", purpose, "hash", hash(code))), 120);
    try {
      BufferedImage img = new BufferedImage(150, 52, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = img.createGraphics();
      g.setColor(new Color(255, 244, 225));
      g.fillRect(0, 0, 150, 52);
      g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 30));
      g.setColor(new Color(130, 60, 30));
      g.drawString(code, 25, 37);
      g.dispose();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(img, "png", out);
      devSecret("captcha", id, code);
      return map(
          "captchaId",
          id,
          "imageBase64",
          "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray()),
          "expiresIn",
          120);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void checkCaptcha(String id, String code, String purpose) {
    String value = temp.take("captcha:" + id);
    require(
        value != null
            && purpose.equals(text(read(value), "purpose"))
            && hash(code).equals(text(read(value), "hash")),
        400,
        "CAPTCHA_INVALID",
        "图片验证码错误或已过期");
  }

  public Map<String, Object> sendLoginSms(Map<String, Object> body, String ip) {
    Input in = new Input(body, "phone,purpose,captchaId,captchaCode,bindTicket");
    String phone = in.phone("phone"), purpose = in.choice("purpose", "login,wechat_bind,password_reset");
    checkCaptcha(in.str("captchaId", 1, 64), in.str("captchaCode", 4, 6), "sms");
    if (purpose.equals("wechat_bind"))
      require(
          temp.get("bind:" + in.str("bindTicket", 1, 200)) != null,
          400,
          "WECHAT_AUTH_INVALID",
          "绑定凭证已失效");
    return sendSms(
        phone, purpose, purpose.equals("wechat_bind") ? in.str("bindTicket", 1, 200) : "", ip);
  }

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

  public Map<String, Object> profile(Actor actor) {
    role(actor, "buyer");
    return profile(store.get("user", actor.id()));
  }

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

  public Map<String, Object> login(Map<String, Object> account, String role) {
    String token = randomToken();
    int seconds = role.equals("admin") ? 1800 : 7200;
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
        role.equals("admin") ? "admin" : "user",
        role.equals("admin")
            ? map("id", account.get("id"), "username", account.get("username"), "role", "admin")
            : profile(account));
  }

  @Transactional
  public Map<String, Object> adminLogin(Map<String, Object> body, String ip) {
    Input in = new Input(body, "username,password,captchaId,captchaCode");
    String username = loginCredential(body, "username", 32);
    String password = loginCredential(body, "password", 128);
    temp.checkFailures("admin-login:" + hash(username), 5);
    checkCaptcha(in.str("captchaId", 1, 64), in.str("captchaCode", 1, 20), "admin_login");
    store.lock();
    Map<String, Object> account = store.byKey("admin", username);
    if (account == null || !passwordMatches(password, text(account, "passwordHash"))) {
      temp.failed("admin-login:" + hash(username), 600);
      throw new ApiException(401, "UNAUTHORIZED", "账号或密码不正确");
    }
    temp.resetFailures("admin-login:" + hash(username));
    return login(account, "admin");
  }

  @Transactional
  public void logout(Actor actor) {
    if (actor == null) return;
    Map<String, Object> session = store.byKey("session", actor.tokenHash());
    if (session != null) {
      session.put("status", "revoked");
      store.save(session);
    }
  }

  public String passwordHash(String password) {
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt) + ":" + derive(password, salt);
  }

  private String derive(String password, byte[] salt) {
    try {
      return Base64.getEncoder()
          .encodeToString(
              SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                  .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 210000, 256))
                  .getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean passwordMatches(String password, String encoded) {
    if (encoded == null || !encoded.contains(":")) return false;
    String[] parts = encoded.split(":");
    return MessageDigest.isEqual(
        derive(password, Base64.getDecoder().decode(parts[0]))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
        parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 登录凭据格式不暴露长度规则，失败时与账号或密码不匹配使用同一提示。 */
  private String loginCredential(Map<String, Object> body, String key, int maxLength) {
    Object value = body == null ? null : body.get(key);
    if (!(value instanceof String text) || text.trim().isEmpty() || text.trim().length() > maxLength)
      throw new ApiException(401, "UNAUTHORIZED", "账号或密码不正确");
    return text.trim();
  }

  /** 密码登录与密码变更共用数据库锁，避免密码重置后旧密码仍签发新会话。 */
  @Transactional
  public Map<String, Object> passwordLogin(Map<String, Object> body, String ip) {
    Input in = new Input(body, "account,password,captchaId,captchaCode");
    String account = loginCredential(body, "account", 32).toLowerCase(Locale.ROOT);
    String password = loginCredential(body, "password", 128);
    temp.limit("password-login-ip:" + ip, 30, 60);
    checkCaptcha(in.str("captchaId", 1, 64), in.str("captchaCode", 1, 20), "password_login");
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

  private String nextChange(Map<String, Object> account, String field, int days) {
    String changed = text(account, field);
    return changed == null || changed.isBlank() ? null : Instant.parse(changed).plus(Duration.ofDays(days)).toString();
  }

  /** 使用服务器时间和持久化时间戳；首次设置允许执行，之后按滚动天数限制。 */
  private void checkCooldown(Map<String, Object> account, String field, int days) {
    String next = nextChange(account, field, days);
    require(next == null || !Instant.parse(next).isAfter(Instant.now()), 429,
        "CHANGE_COOLDOWN", "每" + days + "天只能修改一次，下次可修改时间：" + next);
  }

  private String newPassword(Input in) {
    String password = in.str("newPassword", 12, 128);
    require(password.matches(".*[a-zA-Z].*") && password.matches(".*[0-9].*"),
        400, "VALIDATION_ERROR", "密码须为12至128位，包含字母和数字");
    return password;
  }

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

  /** 重置和首次设置均要求绑定手机号的专用验证码，不接受登录验证码。 */
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

  @Transactional
  public Map<String, Object> changePassword(Actor actor, Map<String, Object> body, String role) {
    role(actor, role);
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
    savePassword(account, password, role);
    temp.resetFailures(key);
    return map("message", "密码已更新，请重新登录");
  }

  /** 写操作取得锁后再次认证，防止等待期间会话已被另一请求撤销。 */
  private void requireActiveSession(Actor actor) {
    Map<String, Object> session = store.byKey("session", actor.tokenHash());
    require(session != null && "active".equals(text(session, "status"))
        && Instant.parse(text(session, "expiresAt")).isAfter(Instant.now()),
        401, "UNAUTHORIZED", "请重新登录");
  }

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

  public Map<String, Object> adminSecurity(Actor actor) {
    role(actor, "admin");
    Map<String, Object> account = store.get("admin", actor.id());
    return map("username", account.get("username"),
        "passwordChangeAvailableAt", nextChange(account, "passwordChangedAt", 7));
  }

  /** 复用受权限保护的开发收件箱；真实模式不能写入模拟通知。 */
  public void localAppointmentNotice(String id, Map<String, Object> notice) {
    require(mock, 503, "SERVICE_UNAVAILABLE", "真实模式不可写入开发短信收件箱");
    devSecret("sms-appointment", id, Json.write(notice));
  }

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
