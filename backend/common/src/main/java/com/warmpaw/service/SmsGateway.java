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
    // 登录与微信绑定使用号码认证赠送模板；交付确认仍使用原短信服务。
    if (purpose.equals("login") || purpose.equals("wechat_bind")) {
      String template = ProviderSupport.env("PAW_PNVS_LOGIN_TEMPLATE");
      sendTemplate(phone, template.isBlank() ? "100001" : template,
          Json.map("code", code, "min", "5"), true);
      return;
    }
    String template = ProviderSupport.env(
        purpose.equals("pickup_confirm") || purpose.equals("exchange_confirm")
            ? "PAW_SMS_CONFIRM_TEMPLATE" : "PAW_SMS_LOGIN_TEMPLATE");
    sendTemplate(phone, template, Json.map("code", code), false);
  }

  /** 预约通知使用独立模板，禁止回退到验证码模板。 */
  public void sendAppointment(String phone, String event, Map<String, Object> parameters) {
    String templateKey = switch (event) {
      case "submitted" -> "PAW_SMS_APPOINTMENT_SUBMITTED_TEMPLATE";
      case "confirmed" -> "PAW_SMS_APPOINTMENT_CONFIRMED_TEMPLATE";
      case "cancelled" -> "PAW_SMS_APPOINTMENT_CANCELLED_TEMPLATE";
      case "expired" -> "PAW_SMS_APPOINTMENT_EXPIRED_TEMPLATE";
      case "completed" -> "PAW_SMS_APPOINTMENT_COMPLETED_TEMPLATE";
      default -> throw new IllegalArgumentException("不支持的预约短信节点");
    };
    sendTemplate(phone, ProviderSupport.env(templateKey), parameters, false);
  }

  private void sendTemplate(String phone, String template, Map<String, Object> parameters, boolean verification) {
    String key = ProviderSupport.env("PAW_SMS_ACCESS_KEY_ID"),
        secret = ProviderSupport.env("PAW_SMS_ACCESS_KEY_SECRET"),
        signName = ProviderSupport.env(verification ? "PAW_PNVS_SIGN_NAME" : "PAW_SMS_SIGN_NAME");
    if (verification && signName.isBlank()) signName = "恒创联众";
    // 未提供短信专用凭据时，成对复用阿里云凭据，避免混用两个账号的 ID 与 Secret。
    if (key.isBlank() && secret.isBlank()) {
      key = ProviderSupport.env("OSS_ACCESS_KEY_ID");
      secret = ProviderSupport.env("OSS_ACCESS_KEY_SECRET");
    }
    require(
        !key.isBlank() && !secret.isBlank() && !signName.isBlank() && !template.isBlank(),
        503,
        "SERVICE_UNAVAILABLE",
        "短信服务尚未配置");
    try {
      Map<String, String> params = new TreeMap<>();
      params.put("AccessKeyId", key);
      params.put("Action", verification ? "SendSmsVerifyCode" : "SendSms");
      params.put("Format", "JSON");
      params.put("Version", "2017-05-25");
      params.put("RegionId", "cn-hangzhou");
      params.put("SignatureMethod", "HMAC-SHA1");
      params.put("SignatureVersion", "1.0");
      params.put("SignatureNonce", UUID.randomUUID().toString());
      params.put(
          "Timestamp", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
      params.put(verification ? "PhoneNumber" : "PhoneNumbers", phone);
      if (verification) {
        // 使用自有验证码，由 Redis 校验；阿里云响应不得返回验证码。
        params.put("CountryCode", "86");
        params.put("ValidTime", "300");
        params.put("Interval", "60");
        params.put("ReturnVerifyCode", "false");
      }
      params.put("SignName", signName);
      params.put("TemplateCode", template);
      params.put("TemplateParam", Json.write(parameters));
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
          HttpRequest.newBuilder(URI.create(verification ? "https://dypnsapi.aliyuncs.com/" : "https://dysmsapi.aliyuncs.com/"))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(query + "&Signature=" + encode(signature)))
              .build();
      HttpResponse<String> response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(10))
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString());
      Map<String, Object> result = Json.read(response.body());
      require(
          response.statusCode() == 200
              && "OK".equals(Json.text(result, "Code"))
              && (!verification || Boolean.TRUE.equals(result.get("Success"))),
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
