package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * 阿里云图形认证二次校验；密钥仅用于服务端签名，异常时不能放行登录或短信。
 *
 * <p><b>流程</b>：前端用阿里云 JS SDK 完成滑块/点选 → SDK 返回四个凭据 →
 * 服务端拿 {@code appKey} 对 {@code lot_number} 做 HMAC-SHA256 生成 {@code sign_token} →
 * 连同四个凭据 POST 到 {@code captcha.alicaptcha.com/validate} →
 * 只有 {@code status=success && result=success} 才放行。
 *
 * <p><b>为什么 sign_token 必须在服务端算</b>：它证明"这次挑战是发给我们这个 appId 的"。
 * 如果由客户端提交，任何知道 appId 的人都能伪造一份合法凭据。同理，{@link #verify}
 * 只用请求体里的四个字段重新组 Map，<b>丢弃</b>客户端自带的一切其它键
 * （包括可能存在的 {@code sign_token}/{@code captcha_id}）——不做这个隔离就等于让客户端
 * 决定校验参数。
 *
 * <p><b>失败关闭</b>：配置缺失 → 503；上游超时/非 200 → 502；校验不通过 → 400。
 * 没有任何一条路径会在"无法确认"时放行——图形验证是短信轰炸与撞库的第一道闸，
 * 它失效时的正确行为是拒绝服务，而不是放行。
 */
@Service
public class GraphCaptchaService {
  /**
   * 前端 SDK 输出、服务端必须校验的<b>四个</b>字段（逗号分隔，供 {@code Input} 白名单与
   * {@code AuthService.sendLoginSms/passwordLogin} 的入参校验复用）。
   *
   * <ul>
   *   <li>{@code lot_number} —— 本次挑战的唯一编号，也是服务端签名与服务端限流键的输入；
   *   <li>{@code captcha_output} —— SDK 生成的答题结果；
   *   <li>{@code pass_token} —— 前端拿到的通过令牌；
   *   <li>{@code gen_time} —— 生成时间，供上游判断时效。
   * </ul>
   *
   * <p>注意它<b>不含</b> {@code sign_token}/{@code captcha_id}：前者由服务端计算，后者由服务端配置下发。
   */
  public static final String FIELDS = "lot_number,captcha_output,pass_token,gen_time";
  private final TemporaryStore temp;

  public GraphCaptchaService(TemporaryStore temp) {
    this.temp = temp;
  }

  /**
   * 客户端只需要公开的认证 ID，绝不能返回 appKey。
   *
   * <p>下发前端初始化参数：只有公开的 {@code captchaId}（即 appId）。
   * appKey 是服务端签名密钥，泄露后任何人都能为任意 {@code lot_number} 生成合法
   * {@code sign_token}，图形验证立刻失效。
   *
   * <p>未配置时抛 503 而不是返回空配置：前端拿不到 captchaId 就无法出题，
   * 让它明确看到"服务未就绪"，比让它在页面上卡住或直接跳过验证好。
   */
  public Map<String, Object> config() {
    String appId = ProviderSupport.env("PAW_CAPTCHA_APP_ID");
    require(!appId.isBlank() && !ProviderSupport.env("PAW_CAPTCHA_APP_KEY").isBlank(),
        503, "SERVICE_UNAVAILABLE", "图形验证尚未配置，请联系管理员");
    return map("captchaId", appId);
  }

  /**
   * 校验一次图形验证，通过则返回；否则抛异常（调用方不需要判断返回值）。
   *
   * <p><b>一次性消费</b>由 {@code temp.limit("graph-captcha-lot:" + hash(lot), 1, 600)} 实现：
   * 同一个 {@code lot_number} 在 10 分钟内只允许通过 1 次，第二次直接被 429 拒绝。
   * 用限流器而不是 {@code take} 是因为这里要的是"同一个 lot 只能被提交一次"，
   * 计数比"存一个标记再删除"语义更直接，并且天然带 TTL。
   *
   * <p>注意计数在<b>调用上游之前</b>自增：所以<b>校验失败也会消耗掉这个 lot</b>，
   * 用户必须重新完成一次滑块。这挡住了"拿同一个 lot 反复猜"的重放攻击，
   * 代价是网络抖动导致的一次误判也需要重新验证——安全优先的选择。
   *
   * <p>字段长度上限各不相同（output 16K、token 2K）：按 SDK 实际输出量给足余量，
   * 同时防止有人塞一个几 MB 的串让服务端去签名/转发。
   *
   * <p>异常映射：上游不可达/超时 → 502（可重试）；上游明确判定不通过 → 400
   * {@code CAPTCHA_INVALID}（需重新验证）。两者分开，前端才能决定是"重试"还是"重做验证"。
   */
  public void verify(Map<String, Object> body) {
    String appId = text(config(), "captchaId");
    // 只读取 SDK 输出的四个字段；忽略客户端自带的 captcha_id 和签名。
    Input proof = new Input(map("lot_number", body.get("lot_number"),
        "captcha_output", body.get("captcha_output"), "pass_token", body.get("pass_token"),
        "gen_time", body.get("gen_time")), FIELDS);
    String lot = proof.str("lot_number", 1, 128);
    Map<String, String> form = new LinkedHashMap<>();
    form.put("lot_number", lot);
    form.put("captcha_output", proof.str("captcha_output", 1, 16384));
    form.put("pass_token", proof.str("pass_token", 1, 2048));
    form.put("gen_time", proof.str("gen_time", 1, 32));
    // Redis 原子计数阻止跨入口及并发重放；失败后必须完成一次新的图形验证。
    temp.limit("graph-captcha-lot:" + hash(lot), 1, 600);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(ProviderSupport.env("PAW_CAPTCHA_APP_KEY")
          .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      form.put("sign_token", HexFormat.of().formatHex(mac.doFinal(lot.getBytes(StandardCharsets.UTF_8))));
      StringJoiner encoded = new StringJoiner("&");
      form.forEach((key, value) -> encoded.add(enc(key) + "=" + enc(value)));
      HttpRequest request = HttpRequest.newBuilder(URI.create(
          "https://captcha.alicaptcha.com/validate?captcha_id=" + enc(appId)))
          .timeout(Duration.ofSeconds(5))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(encoded.toString())).build();
      HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
          .build().send(request, HttpResponse.BodyHandlers.ofString());
      require(response.statusCode() == 200, 502, "UPSTREAM_ERROR", "图形验证服务暂不可用，请重试");
      Map<String, Object> result = read(response.body());
      require("success".equals(result.get("status")) && "success".equals(result.get("result")),
          400, "CAPTCHA_INVALID", "图形验证失败或已过期，请重新验证");
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(502, "UPSTREAM_ERROR", "图形验证服务暂不可用，请重试");
    } catch (Exception e) {
      throw new ApiException(502, "UPSTREAM_ERROR", "图形验证服务暂不可用，请重试");
    }
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
