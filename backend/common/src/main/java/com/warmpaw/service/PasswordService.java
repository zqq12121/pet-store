package com.warmpaw.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

/**
 * 密码哈希与校验，从 {@code AuthService} 中拆出的独立职责。
 *
 * <p><b>为什么单独成类</b>：密码存储格式与算法参数是<b>安全边界</b>，它的变化率与
 * 会话管理、微信登录、短信验证码完全不同——换算法（比如日后加 Argon2）、
 * 改轮数、加 pepper，只需要动这一个文件，不会碰到认证编排逻辑。
 * 拆之前这些方法混在 775 行的 {@code AuthService} 里，任何一次密码策略调整
 * 都要在一个巨型类里定位，且 review 时容易被其他改动淹没。
 *
 * <p><b>为什么无状态</b>：不依赖数据库、Redis 或任何外部服务。所有输入都是
 * 明文密码与已存储的哈希串，输出是布尔值或新哈希。这让它可以被任意单测直接实例化，
 * 也让"密码校验"这件事永远不会因为某个 Spring Bean 没装配好而失效。
 *
 * <p><b>存储格式</b>：{@code base64(salt):base64(derivedKey)}。
 * 冒号分隔，前半是 16 字节随机盐，后半是 PBKDF2 派生结果。
 */
@Service
public class PasswordService {

  /** 盐长度（字节）。16 字节 = 128 位，避免不同账号出现相同盐。 */
  private static final int SALT_BYTES = 16;

  /**
   * PBKDF2 迭代轮数。
   *
   * <p>210000 轮是"当前硬件约 100ms"的量级：太低挡不住离线爆破，
   * 太高会让登录接口本身变成 DoS 面（每次请求都烧 100ms 以上的 CPU）。
   * 调整这个值时必须同步考虑存量哈希——已有用户仍按旧轮数校验，
   * 不能直接改，否则所有人密码失效。
   */
  private static final int ITERATIONS = 210_000;

  /** 派生密钥长度（位）。256 位与 SHA-256 输出一致。 */
  private static final int KEY_BITS = 256;

  private final SecureRandom random = new SecureRandom();

  /**
   * 生成密码哈希：每个密码独立随机盐，相同明文产生不同结果，防彩虹表。
   */
  public String hash(String password) {
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt) + ":" + derive(password, salt);
  }

  /**
   * 恒定时间比对密码与存储的哈希。
   *
   * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
   * 后者在首个不同字节处返回，攻击者可以通过响应时间差逐字节猜测哈希。
   * 这里比对的是派生结果（本身是哈希），保持恒定时间仍是好习惯。
   *
   * <p>格式不合法（无冒号、盐无法解码）一律返回 {@code false} 而不抛异常——
   * 数据库里出现脏数据时应表现为"密码不正确"，而不是 500 让整个登录接口挂掉。
   */
  public boolean matches(String password, String encoded) {
    if (encoded == null || !encoded.contains(":")) return false;
    String[] parts = encoded.split(":");
    if (parts.length != 2) return false;
    try {
      return MessageDigest.isEqual(
          derive(password, Base64.getDecoder().decode(parts[0])).getBytes(StandardCharsets.UTF_8),
          parts[1].getBytes(StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      // Base64 解码失败：把脏数据当作"密码不匹配"，与上面的约定一致。
      return false;
    }
  }

  /**
   * 用给定盐派生密钥。抽出来是为了让"生成哈希"和"校验哈希"用完全相同的参数——
   * 两处参数不一致会导致所有密码都无法通过校验，且极难发现。
   */
  private String derive(String password, byte[] salt) {
    try {
      return Base64.getEncoder()
          .encodeToString(
              SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                  .generateSecret(new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS))
                  .getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
