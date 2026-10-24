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

/**
 * 微信支付 API v3 接入层：负责请求签名、响应验签、回调通知验签与 AES-GCM 解密。
 *
 * <p>这一层只做"协议正确"，不含任何业务判断——什么时候下单、金额多少、要不要退，
 * 都由 {@link PaymentService} 决定。这样换支付渠道时只需要替换本类。
 *
 * <p><b>密钥只从环境变量读取</b>，不读配置文件、不进代码库：
 *
 * <pre>
 *   PAW_WECHAT_APP_ID / PAW_WECHAT_MCH_ID / PAW_WECHAT_MCH_SERIAL
 *   PAW_WECHAT_PRIVATE_KEY_PATH / PAW_WECHAT_PUBLIC_KEY_PATH / PAW_WECHAT_PUBLIC_KEY_ID
 *   PAW_WECHAT_API_V3_KEY（32 位）/ PAW_PUBLIC_BASE_URL（必须是 https）
 * </pre>
 *
 * <p><b>失败关闭</b>：{@link #enabled} 要求八项配置全部就位且场景开关为 true，
 * 缺任何一项都视为不可用。宁可让支付入口不可点，也不能带着半套配置去调平台。
 *
 * <p><b>安全要点</b>：
 *
 * <ul>
 *   <li>回调验签校验 {@code Wechatpay-Serial} 必须等于已登记的商户平台证书序列号，
 *       防止用攻击者自签证书伪造通知；
 *   <li>时间戳偏差超过 300 秒直接拒绝，防重放；
 *   <li>连响应也验签——不只信 HTTPS，还要确认报文确实来自微信。
 * </ul>
 */
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


  /** 公众号/小程序 appid，同时用作 openid 缓存键的前缀。 */
  public String appId() {
    return appId;
  }

  /**
   * 判断某个支付场景是否可用。
   *
   * <p>这里刻意把八项配置写成一条长布尔表达式而不是分散校验：调用方只需要一个"能用/不能用"的结论。
   * {@code apiKey.length() == 32} 是 AES-256 的硬性要求；{@code notifyBase} 必须是 https，
   * 因为微信不接受 http 回调地址。
   */
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

  /** 读取 PKCS#8 私钥并去掉 PEM 头尾与换行；文件不存在或格式错误会在调用处转成 503。 */
  private PrivateKey privateKey() throws Exception {
    String pem = Files.readString(Path.of(privatePath)).replaceAll("-----[^-]+-----|\\s", "");
    return KeyFactory.getInstance("RSA")
        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
  }

  /** 读取商户平台证书公钥（X.509），用于验证微信回调与响应。 */
  private PublicKey publicKey() throws Exception {
    String pem = Files.readString(Path.of(publicPath)).replaceAll("-----[^-]+-----|\\s", "");
    return KeyFactory.getInstance("RSA")
        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
  }

  /** SHA256withRSA 签名，返回 Base64。签名失败按"配置不可用"处理（503），不降级为不签名。 */
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

  /**
   * 验证回调/响应的签名。待签串是固定格式：
   *
   * <pre>timestamp + "\n" + nonce + "\n" + body + "\n"</pre>
   *
   * <p>四道检查：序列号必须是已登记的那一张、三个头部字段不能为空、时间戳偏差 ≤ 300 秒、
   * 最后才做 RSA 验签。任何一步异常统一返回 400，不区分具体原因——避免给攻击者提供调试信息。
   */
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

  /**
   * 发起一次带签名的 API 调用。
   *
   * <p>流程：构造签名串（{@code method\npath\ntimestamp\nnonce\nbody\n}）→ 组 Authorization 头 →
   * 发送 → <b>对响应体也验签</b> → 判断状态码。
   *
   * <p>两个特殊处理：
   *
   * <ul>
   *   <li>平台返回 404 且错误码为 {@code ORDER_NOT_EXIST}/{@code RESOURCE_NOT_EXISTS} 时，
   *       转成 502 {@code UPSTREAM_NOT_FOUND}，让上层能区分"单号不存在"和"网络故障"；
   *   <li>网络异常与超时统一抛 502 {@code UPSTREAM_ERROR} 并提示"结果暂不明确"——
   *       调用方（{@link PaymentWorker}）据此保留状态等待查询，而不是当失败处理。
   * </ul>
   *
   * @param payload 为 null 表示无请求体（GET / 关单等，关单实际用空 map 传 {@code mchid}）
   */
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
      // 保留中断标记，否则线程池里的中断信号会被吞掉。
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ApiException(502, "UPSTREAM_ERROR", "微信支付结果暂不明确，请稍后查询");
    }
  }

  /**
   * 统一下单。
   *
   * <p>JSAPI 与 H5 的差异只在最后一段：JSAPI 需要 {@code payer.openid} 并返回可调起收银台的
   * 签名参数（{@code invokeParams}）；H5 需要 {@code scene_info}（客户端 IP 与 Wap 类型）并返回 {@code h5Url}。
   *
   * <p>返回的 {@code paySign} 用另一套待签串（appId\ntimeStamp\nnonceStr\npackage\n），
   * 与 API 请求签名不同，不能混用。
   *
   * <p>{@code time_expire} 直接取订单的过期时间，让平台侧和本地侧同时失效，避免"本地已关单、平台还能付"。
   */
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

  /** 按商户订单号查询支付结果，是"结果未知"时唯一的真相来源。 */
  public Map<String, Object> queryPayment(Map<String, Object> p) {
    return request(
        "GET",
        "/v3/pay/transactions/out-trade-no/" + text(p, "outTradeNo") + "?mchid=" + merchant,
        null);
  }

  /** 关闭平台侧支付单。关单后平台不能再收款，是本地释放库存的前提。 */
  public void close(Map<String, Object> p) {
    request(
        "POST",
        "/v3/pay/transactions/out-trade-no/" + text(p, "outTradeNo") + "/close",
        map("mchid", merchant));
  }

  /**
   * 申请退款。{@code amount.total} 必须是原订单总额、{@code amount.refund} 是本次退款额——
   * 微信要求两个数都给，只给退款额会被拒。
   *
   * <p>{@code out_refund_no} 用本地生成的 {@code refundNo}，保证重复调用是同一笔退款（平台侧幂等）。
   */
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

  /** 按退款单号查询退款进度。 */
  public Map<String, Object> queryRefund(Map<String, Object> refund) {
    return request("GET", "/v3/refund/domestic/refunds/" + text(refund, "refundNo"), null);
  }

  /**
   * 解密回调通知里的 {@code resource}。
   *
   * <p>AES-256-GCM，密钥是 APIv3 密钥，nonce 与 associated_data 都来自通知本身，
   * 认证标签长度固定 128 位。{@code doFinal} 会同时校验完整性，篡改过的密文会直接抛异常。
   *
   * <p>先校验 {@code algorithm == AEAD_AES_256_GCM}：不接受平台未来可能新增的算法，
   * 避免"算法混淆"类攻击。
   */
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

  /**
   * 校验报文里的商户号与 appid 是否属于本店。
   *
   * <p>验签只能证明"报文来自微信"，不能证明"这笔单是开给我们的"。必须再核对这两个字段，
   * 否则别家商户的合法通知也可能被当成自己的。
   */
  public void checkMerchant(Map<String, Object> p) {
    require(
        merchant.equals(text(p, "mchid")) && appId.equals(text(p, "appid")),
        400,
        "INVALID_ARGUMENT",
        "支付身份不匹配");
  }

  /** 商户号，供 openid 缓存键等场景使用。 */
  public String merchant() {
    return merchant;
  }
}
