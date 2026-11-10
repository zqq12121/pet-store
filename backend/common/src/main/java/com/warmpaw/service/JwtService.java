package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;

import com.warmpaw.common.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 的签发与校验（HS256）。
 *
 * <p><b>载荷</b>：{@code sub}=账号 id，{@code role}=buyer/staff/admin，{@code jti}=本 token 唯一 id，
 * {@code iat}/{@code exp}=签发/过期时间，{@code iatMs}=毫秒级签发时间。
 *
 * <p><b>为什么还要 iatMs</b>：改密码、停用员工时要让"此刻之前签发的所有 token"失效，
 * 判断依据是签发时间。标准 {@code iat} 只精确到秒，用户改完密码在同一秒内重新登录，
 * 新 token 会被误判为旧 token。
 *
 * <p><b>密钥</b>：order-server 和 admin-server 必须配置同一个 {@code PAW_JWT_SECRET}，
 * 否则一边签发的 token 另一边验不过。生产环境（非 mock 模式）未配置时启动失败，
 * 不允许用内置的开发密钥上线。
 */
@Component
public class JwtService {

  private static final Logger log = LoggerFactory.getLogger(JwtService.class);

  /** 仅本地/测试使用的固定密钥，生产环境启动时会被拒绝。 */
  private static final String DEV_SECRET = "warmpaw-local-development-jwt-secret-do-not-use";

  /** 解析后的 token 内容。 */
  public record Token(String id, String subject, String role, long issuedAtMs, Instant expiresAt) {}

  private final SecretKey key;

  public JwtService(
      @Value("${app.jwt-secret:}") String secret, @Value("${app.mock-providers}") boolean mock) {
    if (secret == null || secret.isBlank()) {
      if (!mock) throw new IllegalStateException("未配置 PAW_JWT_SECRET（至少 32 字节），生产环境不能使用开发密钥");
      log.warn("未配置 PAW_JWT_SECRET，使用本地开发密钥；仅限本地和测试环境");
      secret = DEV_SECRET;
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    // HS256 要求密钥至少 256 位，短密钥 jjwt 会直接拒绝，这里给出更明确的提示
    if (bytes.length < 32) throw new IllegalStateException("PAW_JWT_SECRET 至少需要 32 字节");
    this.key = Keys.hmacShaKeyFor(bytes);
  }

  /** 签发 token，返回紧凑格式字符串。 */
  public String issue(String subject, String role, int seconds) {
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .id(UUID.randomUUID().toString())
        .subject(subject)
        .claim("role", role)
        .claim("iatMs", now)
        .issuedAt(new Date(now))
        .expiration(new Date(now + seconds * 1000L))
        .signWith(key)
        .compact();
  }

  /**
   * 校验签名和过期时间并解析。
   *
   * <p>过期返回 401 {@code TOKEN_EXPIRED}（前端据此提示"登录已过期"），
   * 签名不对、格式错误、被篡改一律 401 {@code UNAUTHORIZED}。
   */
  public Token parse(String token) {
    Claims claims;
    try {
      claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    } catch (ExpiredJwtException e) {
      throw new ApiException(401, "TOKEN_EXPIRED", "登录已过期");
    } catch (JwtException | IllegalArgumentException e) {
      throw new ApiException(401, "UNAUTHORIZED", "请先登录");
    }
    Object iatMs = claims.get("iatMs");
    require(
        claims.getId() != null
            && claims.getSubject() != null
            && claims.get("role") instanceof String
            && iatMs instanceof Number,
        401,
        "UNAUTHORIZED",
        "请先登录");
    return new Token(
        claims.getId(),
        claims.getSubject(),
        (String) claims.get("role"),
        ((Number) iatMs).longValue(),
        claims.getExpiration().toInstant());
  }
}
