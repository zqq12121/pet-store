package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;
import static com.warmpaw.common.ProviderSupport.env;

import com.warmpaw.common.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;

/** 微信 API v3 接入：请求签名、响应验签、通知验签和 AES-GCM 解密。密钥仅从环境读取。 */
@Component
public class WechatGateway {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final String appId = env("PAW_WECHAT_APP_ID"),
      merchant = env("PAW_WECHAT_MCH_ID"),
      serial = env("PAW_WECHAT_MCH_SERIAL"),
      privatePath = env("PAW_WECHAT_PRIVATE_KEY_PATH"),
      publicPath = env("PAW_WECHAT_PUBLIC_KEY_PATH"),
      publicSerial = env("PAW_WECHAT_PUBLIC_KEY_ID"),
      apiKey = env("PAW_WECHAT_API_V3_KEY"),
      notifyBase = env("PAW_PUBLIC_BASE_URL");


  public String appId() {
    return appId;
  }

  public boolean enabled(String scene) {
    return !appId.isBlank()
        && !merchant.isBlank()
        && !serial.isBlank()
        && !privatePath.isBlank()
        && !publicPath.isBlank()
        && !publicSerial.isBlank()
        && apiKey.length() == 32
        && notifyBase.startsWith("https://")
        && "true"
            .equalsIgnoreCase(
                env(scene.equals("h5") ? "PAW_WECHAT_H5_ENABLED" : "PAW_WECHAT_JSAPI_ENABLED"));
  }

  private PrivateKey privateKey() throws Exception {
    String pem = Files.readString(Path.of(privatePath)).replaceAll("-----[^-]+-----|\\s", "");
    return KeyFactory.getInstance("RSA")
        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
  }

  private PublicKey publicKey() throws Exception {
    String pem = Files.readString(Path.of(publicPath)).replaceAll("-----[^-]+-----|\\s", "");
    return KeyFactory.getInstance("RSA")
        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
  }

  private String sign(String text) {
    try {
      Signature s = Signature.getInstance("SHA256withRSA");
      s.initSign(privateKey());
      s.update(text.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(s.sign());
    } catch (Exception e) {
      throw new ApiException(503, "SERVICE_UNAVAILABLE", "微信支付签名配置不可用");
    }
  }

  public void verify(String timestamp, String nonce, String serial, String signature, String body) {
    try {
      require(
          serial != null
              && serial.equals(publicSerial)
              && timestamp != null
              && nonce != null
              && signature != null,
          400,
          "INVALID_ARGUMENT",
          "通知验证失败");
      require(
          Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) <= 300,
          400,
          "INVALID_ARGUMENT",
          "通知验证失败");
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey());
      verifier.update(
          (timestamp + "\n" + nonce + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
      require(
          verifier.verify(Base64.getDecoder().decode(signature)),
          400,
          "INVALID_ARGUMENT",
          "通知验证失败");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(400, "INVALID_ARGUMENT", "通知验证失败");
    }
  }

  private Map<String, Object> request(String method, String path, Map<String, Object> payload) {
    require(!merchant.isBlank(), 503, "SERVICE_UNAVAILABLE", "微信支付尚未配置");
    String body = payload == null ? "" : write(payload),
        nonce = UUID.randomUUID().toString().replace("-", ""),
        timestamp = Long.toString(Instant.now().getEpochSecond());
    String signature =
        sign(method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n");
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("https://api.mch.weixin.qq.com" + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header(
                "Authorization",
                "WECHATPAY2-SHA256-RSA2048 mchid=\""
                    + merchant
                    + "\",nonce_str=\""
                    + nonce
                    + "\",timestamp=\""
                    + timestamp
                    + "\",serial_no=\""
                    + serial
                    + "\",signature=\""
                    + signature
                    + "\"")
            .method(
                method,
                body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
      verify(
          response.headers().firstValue("Wechatpay-Timestamp").orElse(null),
          response.headers().firstValue("Wechatpay-Nonce").orElse(null),
          response.headers().firstValue("Wechatpay-Serial").orElse(null),
          response.headers().firstValue("Wechatpay-Signature").orElse(null),
          response.body());
      if (response.statusCode() == 404
          && !response.body().isBlank()
          && List.of("ORDER_NOT_EXIST", "RESOURCE_NOT_EXISTS")
              .contains(text(read(response.body()), "code")))
        throw new ApiException(502, "UPSTREAM_NOT_FOUND", "平台确认该单号不存在");
      require(
          response.statusCode() >= 200 && response.statusCode() < 300,
          502,
          "UPSTREAM_ERROR",
          "微信支付未受理，请稍后查询结果");
      return response.body().isBlank() ? map() : read(response.body());
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(502, "UPSTREAM_ERROR", "微信支付结果暂不明确，请稍后查询");
    }
  }

  public Map<String, Object> prepay(
      Map<String, Object> order, Map<String, Object> payment, String ip, String openid) {
    String scene = text(payment, "scene");
    require(enabled(scene), 422, "PAYMENT_SCENE_UNAVAILABLE", "此支付场景尚未开通");
    Map<String, Object> b =
        map(
            "appid",
            appId,
            "mchid",
            merchant,
            "description",
            "暖爪宠物门店订单",
            "out_trade_no",
            payment.get("outTradeNo"),
            "notify_url",
            notifyBase + "/api/v1/callbacks/wechat-pay/payments",
            "time_expire",
            order.get("expiresAt"),
            "amount",
            map("total", order.get("amount"), "currency", "CNY"));
    if (scene.equals("jsapi")) {
      require(openid != null, 409, "WECHAT_AUTH_REQUIRED", "请先完成微信授权");
      b.put("payer", map("openid", openid));
    } else b.put("scene_info", map("payer_client_ip", ip, "h5_info", map("type", "Wap")));
    Map<String, Object> result = request("POST", "/v3/pay/transactions/" + scene, b);
    if (scene.equals("h5")) return map("h5Url", result.get("h5_url"));
    String timestamp = Long.toString(Instant.now().getEpochSecond()),
        nonce = UUID.randomUUID().toString().replace("-", ""),
        pkg = "prepay_id=" + result.get("prepay_id");
    return map(
        "invokeParams",
        map(
            "appId",
            appId,
            "timeStamp",
            timestamp,
            "nonceStr",
            nonce,
            "package",
            pkg,
            "signType",
            "RSA",
            "paySign",
            sign(appId + "\n" + timestamp + "\n" + nonce + "\n" + pkg + "\n")));
  }

  public Map<String, Object> queryPayment(Map<String, Object> p) {
    return request(
        "GET",
        "/v3/pay/transactions/out-trade-no/" + text(p, "outTradeNo") + "?mchid=" + merchant,
        null);
  }

  public void close(Map<String, Object> p) {
    request(
        "POST",
        "/v3/pay/transactions/out-trade-no/" + text(p, "outTradeNo") + "/close",
        map("mchid", merchant));
  }

  public Map<String, Object> refund(
      Map<String, Object> refund, Map<String, Object> payment, Map<String, Object> order) {
    return request(
        "POST",
        "/v3/refund/domestic/refunds",
        map(
            "out_trade_no",
            payment.get("outTradeNo"),
            "out_refund_no",
            refund.get("refundNo"),
            "reason",
            "门店售后退款",
            "notify_url",
            notifyBase + "/api/v1/callbacks/wechat-pay/refunds",
            "amount",
            map("refund", refund.get("amount"), "total", order.get("amount"), "currency", "CNY")));
  }

  public Map<String, Object> queryRefund(Map<String, Object> refund) {
    return request("GET", "/v3/refund/domestic/refunds/" + text(refund, "refundNo"), null);
  }

  public Map<String, Object> decrypt(Map<String, Object> notification) {
    try {
      Map<String, Object> r = object(notification, "resource");
      require("AEAD_AES_256_GCM".equals(text(r, "algorithm")), 400, "INVALID_ARGUMENT", "通知验证失败");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "AES"),
          new GCMParameterSpec(128, text(r, "nonce").getBytes(StandardCharsets.UTF_8)));
      cipher.updateAAD(
          Objects.toString(r.get("associated_data"), "").getBytes(StandardCharsets.UTF_8));
      return read(
          new String(
              cipher.doFinal(Base64.getDecoder().decode(text(r, "ciphertext"))),
              StandardCharsets.UTF_8));
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(400, "INVALID_ARGUMENT", "通知验证失败");
    }
  }

  public void checkMerchant(Map<String, Object> p) {
    require(
        merchant.equals(text(p, "mchid")) && appId.equals(text(p, "appid")),
        400,
        "INVALID_ARGUMENT",
        "支付身份不匹配");
  }

  public String merchant() {
    return merchant;
  }
}
