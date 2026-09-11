package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
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

  private final Store store;
  private final TemporaryStore temp;
  private final boolean mock;
  private final SmsGateway smsGateway;
  private final SecureRandom random = new SecureRandom();

  public AuthService(
      Store store,
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
    require(List.of("sms", "admin_login").contains(purpose), 400, "VALIDATION_ERROR", "验证码用途不正确");
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
    String phone = in.phone("phone"), purpose = in.choice("purpose", "login,wechat_bind");
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
    temp.put(
        "sms:" + id,
        write(map("phone", phone, "purpose", purpose, "scope", scope, "hash", hash(code))),
        300);
    if (!mock) smsGateway.send(phone, code, purpose);
    devSecret("sms", id, code);
    return map("smsRequestId", id, "expiresIn", 300, "retryAfter", 60, "phoneMasked", mask(phone));
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
    store.mapper.lock();
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
    String username = in.str("username", 3, 32), password = in.str("password", 8, 128);
    temp.checkFailures("admin-login:" + hash(username), 5);
    checkCaptcha(in.str("captchaId", 1, 64), in.str("captchaCode", 1, 20), "admin_login");
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
    String[] parts = encoded.split(":");
    return MessageDigest.isEqual(
        derive(password, Base64.getDecoder().decode(parts[0]))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
        parts[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
