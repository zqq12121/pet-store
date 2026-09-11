package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;

import com.warmpaw.common.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** 阿里云短信 RPC 签名发送；生产验证码不写入开发收件箱。 */
@Component
public class SmsGateway {
  public void send(String phone, String code, String purpose) {
    String key = WechatGateway.env("PAW_SMS_ACCESS_KEY_ID"),
        secret = WechatGateway.env("PAW_SMS_ACCESS_KEY_SECRET"),
        signName = WechatGateway.env("PAW_SMS_SIGN_NAME"),
        template =
            WechatGateway.env(
                purpose.equals("pickup_confirm") || purpose.equals("exchange_confirm")
                    ? "PAW_SMS_CONFIRM_TEMPLATE"
                    : "PAW_SMS_LOGIN_TEMPLATE");
    require(
        !key.isBlank() && !secret.isBlank() && !signName.isBlank() && !template.isBlank(),
        503,
        "SERVICE_UNAVAILABLE",
        "短信服务尚未配置");
    try {
      Map<String, String> params = new TreeMap<>();
      params.put("AccessKeyId", key);
      params.put("Action", "SendSms");
      params.put("Format", "JSON");
      params.put("Version", "2017-05-25");
      params.put("RegionId", "cn-hangzhou");
      params.put("SignatureMethod", "HMAC-SHA1");
      params.put("SignatureVersion", "1.0");
      params.put("SignatureNonce", UUID.randomUUID().toString());
      params.put(
          "Timestamp", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
      params.put("PhoneNumbers", phone);
      params.put("SignName", signName);
      params.put("TemplateCode", template);
      params.put("TemplateParam", Json.write(Json.map("code", code)));
      String query =
          params.entrySet().stream()
              .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
              .collect(java.util.stream.Collectors.joining("&"));
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      String signature =
          Base64.getEncoder()
              .encodeToString(
                  mac.doFinal(("POST&%2F&" + encode(query)).getBytes(StandardCharsets.UTF_8)));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("https://dysmsapi.aliyuncs.com/"))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(query + "&Signature=" + encode(signature)))
              .build();
      HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(10))
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString());
      require(
          response.statusCode() == 200
              && "OK".equals(Json.text(Json.read(response.body()), "Code")),
          502,
          "UPSTREAM_ERROR",
          "短信平台暂未受理，请稍后重试");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(502, "UPSTREAM_ERROR", "短信发送暂不可用");
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~");
  }
}
