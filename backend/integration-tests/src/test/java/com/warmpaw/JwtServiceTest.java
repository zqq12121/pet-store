package com.warmpaw;

import static org.junit.jupiter.api.Assertions.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.service.JwtService;
import org.junit.jupiter.api.Test;

/** JWT 签发、校验与密钥配置的单元测试。 */
class JwtServiceTest {

  private static final String SECRET = "integration-test-only-jwt-secret-0123456789";
  private final JwtService jwt = new JwtService(SECRET, true);

  @Test
  void issuedTokenRoundTrips() {
    long before = System.currentTimeMillis();
    JwtService.Token token = jwt.parse(jwt.issue("user_1", "buyer", 60));
    assertEquals("user_1", token.subject());
    assertEquals("buyer", token.role());
    assertNotNull(token.id());
    assertTrue(token.issuedAtMs() >= before);
  }

  @Test
  void everyTokenHasItsOwnId() {
    // 退出登录按 jti 拉黑，同一账号两次登录的 token 必须能区分
    assertNotEquals(
        jwt.parse(jwt.issue("user_1", "buyer", 60)).id(),
        jwt.parse(jwt.issue("user_1", "buyer", 60)).id());
  }

  @Test
  void tamperedOrForeignTokensAreRejected() {
    String token = jwt.issue("user_1", "buyer", 60);
    // 改载荷里的一个字符，签名就对不上了
    String[] parts = token.split("\\.");
    char flipped = parts[1].charAt(5) == 'A' ? 'B' : 'A';
    String tampered =
        parts[0] + "." + parts[1].substring(0, 5) + flipped + parts[1].substring(6) + "." + parts[2];
    assertEquals("UNAUTHORIZED", assertThrows(ApiException.class, () -> jwt.parse(tampered)).code);
    // 别的密钥签的 token 不能通过
    JwtService other = new JwtService("another-secret-that-is-long-enough-0123456789", true);
    assertEquals(
        "UNAUTHORIZED",
        assertThrows(ApiException.class, () -> jwt.parse(other.issue("user_1", "admin", 60))).code);
    assertEquals(401, assertThrows(ApiException.class, () -> jwt.parse("not-a-jwt")).status);
  }

  @Test
  void expiredTokenHasDistinctCode() {
    String expired = jwt.issue("user_1", "buyer", -60);
    ApiException e = assertThrows(ApiException.class, () -> jwt.parse(expired));
    assertEquals(401, e.status);
    assertEquals("TOKEN_EXPIRED", e.code);
  }

  @Test
  void productionRefusesMissingOrShortSecret() {
    // 非 mock 模式不允许回退到开发密钥
    assertThrows(IllegalStateException.class, () -> new JwtService("", false));
    assertThrows(IllegalStateException.class, () -> new JwtService("too-short", false));
    // 本地/测试模式可以不配置
    assertNotNull(new JwtService("", true).issue("user_1", "buyer", 60));
  }
}
