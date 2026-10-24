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

/**
 * 阿里云短信 RPC 签名发送；生产验证码不写入开发收件箱。
 *
 * <p><b>两个上游、两套签名参数</b>：
 *
 * <ul>
 *   <li>验证码类（{@code login}/{@code wechat_bind}/{@code password_reset}）走
 *       {@code dypnsapi.aliyuncs.com} 的 {@code SendSmsVerifyCode}（号码认证赠送模板），
 *       传 {@code PhoneNumber} + {@code CountryCode}/{@code ValidTime}/{@code Interval}；
 *   <li>其余（自提确认、兑换确认、预约通知）走 {@code dysmsapi.aliyuncs.com} 的 {@code SendSms}，
 *       传 {@code PhoneNumbers}。
 * </ul>
 *
 * <p><b>配置缺失时失败关闭</b>：AccessKey/Secret/签名/模板任一为空就抛 503
 * {@code SERVICE_UNAVAILABLE}，<b>绝不降级成"假装发送成功"</b>。因为调用方（{@code AuthService}）
 * 在发送成功后才把验证码写进临时存储——如果这里静默成功，用户会进入"服务说发了、手机没收到、
 * 重试还被限流"的死局。本地模拟发送由 {@code app.mock-providers} 在调用方短路，不在这里。
 *
 * <p><b>凭据回退</b>：短信专用凭据未配置时成对复用 OSS 凭据。必须是"成对"回退——
 * 只配了一个变量就混用两个账号的 ID/Secret，签名会稳定失败且极难排查。
 *
 * <p>不重试：单次调用、失败即 502，由客户端重试。服务端重试会掩盖上游故障，
 * 也会让"发送失败不落库"这条正确性依赖变得难以推理。
 */
@Component
public class SmsGateway {
  /**
   * 发送验证码短信。
   *
   * <p>用途到模板的映射见方法体；{@code login}/{@code wechat_bind}/{@code password_reset}
   * 三种走号码认证通道，其余走标准短信通道。
   *
   * <p>号码认证通道未配置模板时回落到 {@code "100001"}——阿里云号码认证的赠送模板编号，
   * 这样开发/小规模部署不配模板也能收到码。其它用途<b>没有</b>回落值，模板为空即 503。
   *
   * <p>注意 {@code verification=true} 时并不使用平台生成的验证码（{@code ReturnVerifyCode=false}），
   * 真正的验证码由本服务生成、存在 Redis、由本服务校验；平台只负责投递。
   */
  public void send(String phone, String code, String purpose) {
    // 登录与微信绑定使用号码认证赠送模板；交付确认仍使用原短信服务。
    if (purpose.equals("login") || purpose.equals("wechat_bind") || purpose.equals("password_reset")) {
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

  /**
   * 预约通知使用独立模板，禁止回退到验证码模板。
   *
   * <p>为什么强调"禁止回退"：预约通知是<b>营销/服务类</b>短信，模板必须过审且带退订标识；
   * 一旦误用验证码模板，会被运营商判为违规发送，可能导致整个签名被封。
   * 所以这里宁可抛异常（模板没配就 503）也不做任何默认值兜底。
   *
   * <p>{@code event} 只接受五个节点，其它值抛 {@link IllegalArgumentException}（→ 500）：
   * 节点由服务端枚举，不属于用户输入，出现未知值说明调用方写错了。
   *
   * <p>{@code parameters} 直接序列化成 {@code TemplateParam}，由调用方决定字段名，
   * 必须与阿里云控制台里模板变量的拼写完全一致。
   */
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

  /**
   * 两种通道共用的发送实现：解析配置 → 组装参数 → 阿里云 RPC v1 签名 → POST → 校验响应。
   *
   * <p>签名按阿里云 RPC 规范：参数名升序用 {@link TreeMap} 排列，percent-encode 后拼成
   * {@code k=v&k=v}，再对 {@code "POST&%2F&" + encode(query)} 做 HMAC-SHA1
   * （密钥是 {@code secret + "&"}），最后 base64 作为 {@code Signature} 追加到表单体。
   * 任何一步的编码差异都会导致 {@code SignatureDoesNotMatch}，所以 {@link #encode} 里
   * 只把 {@code +}、{@code *}、{@code %7E} 换回阿里云要求的形态（Java 的
   * {@code URLEncoder} 按表单规则编码，与 RPC 规范不完全一致）。
   *
   * <p>响应判定偏严格：HTTP 200 + {@code Code=OK}，验证码通道还要求 {@code Success=true}
   * （号码认证接口即使受理失败也可能回 200）。
   *
   * <p>异常处理：{@link ApiException} 原样上抛（保留 502 语义与文案）；
   * {@link InterruptedException} 先恢复线程中断标记再包成 502——吞掉中断标记会让
   * 线程池无法正常停机。
   */
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

  /** 按阿里云 RPC 规范的 percent-encode：在 {@code URLEncoder} 之上修正三个差异字符。 */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~");
  }
}
