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

/** 阿里云图形认证二次校验；密钥仅用于服务端签名，异常时不能放行登录或短信。 */
@Service
public class GraphCaptchaService {
  public static final String FIELDS = "lot_number,captcha_output,pass_token,gen_time";
  private final TemporaryStore temp;

  public GraphCaptchaService(TemporaryStore temp) {
    this.temp = temp;
  }

  /** 客户端只需要公开的认证 ID，绝不能返回 appKey。 */
  public Map<String, Object> config() {
    String appId = ProviderSupport.env("PAW_CAPTCHA_APP_ID");
    require(!appId.isBlank() && !ProviderSupport.env("PAW_CAPTCHA_APP_KEY").isBlank(),
        503, "SERVICE_UNAVAILABLE", "图形验证尚未配置，请联系管理员");
    return map("captchaId", appId);
  }

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
