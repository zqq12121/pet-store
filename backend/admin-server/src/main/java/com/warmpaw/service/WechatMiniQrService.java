package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 小程序扫码只传短期场景号；浏览器独立持有领取凭据，手机不能领取网站令牌。 */
@Service
public class WechatMiniQrService {
  private final TemporaryStore temp;
  private final AuthService auth;
  private final GraphCaptchaService captcha;
  private final BusinessRepository store;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public WechatMiniQrService(TemporaryStore temp, AuthService auth, GraphCaptchaService captcha, BusinessRepository store) {
    this.temp = temp; this.auth = auth; this.captcha = captcha; this.store = store;
  }

  public boolean configured() {
    return !ProviderSupport.env("PAW_WECHAT_MINI_APP_ID").isBlank()
        && !ProviderSupport.env("PAW_WECHAT_MINI_APP_SECRET").isBlank();
  }

  public Map<String, Object> create(Map<String, Object> body, String ip) {
    Input in = new Input(body, "returnPath," + GraphCaptchaService.FIELDS);
    String path = in.str("returnPath", 1, 500);
    ProviderSupport.safePath(path);
    require(configured(), 503, "SERVICE_UNAVAILABLE", "小程序扫码登录尚未配置");
    temp.limit("mini-qr-create:" + ip, 10, 60);
    captcha.verify(body);
    // 微信 scene 上限为 32 字符；独立领取凭据不进入二维码或图片 URL。
    String ticket = UUID.randomUUID().toString().replace("-", ""), browser = auth.randomToken();
    String code = auth.digits(6);
    String image = codeImage(ticket);
    temp.put("mini-qr:" + ticket, write(map("status", "pending", "browser", hash(browser),
        "appId", ProviderSupport.env("PAW_WECHAT_MINI_APP_ID"), "code", code, "returnPath", path,
        "expiresAt", Instant.now().plusSeconds(300).toString())), 300);
    return map("ticket", ticket, "browserToken", browser, "image", image, "verificationCode", code, "expiresIn", 300);
  }

  private String saved(String ticket) {
    require(ticket.matches("[a-f0-9]{32}"), 400, "VALIDATION_ERROR", "扫码场景无效");
    String value = temp.get("mini-qr:" + ticket);
    require(value != null && Instant.parse(text(read(value), "expiresAt")).isAfter(Instant.now()),
        410, "QR_EXPIRED", "二维码已过期，请在电脑上刷新");
    require(ProviderSupport.env("PAW_WECHAT_MINI_APP_ID").equals(text(read(value), "appId")),
        410, "QR_EXPIRED", "登录配置已变更，请重新扫码");
    return value;
  }

  public Map<String, Object> inspect(String ticket, String ip) {
    temp.limit("mini-qr-inspect:" + ip, 60, 60);
    var state = read(saved(ticket));
    return map("status", state.get("status"), "verificationCode", state.get("code"), "expiresAt", state.get("expiresAt"));
  }

  public Map<String, Object> confirm(String ticket, Map<String, Object> body, AuthService.Actor actor) {
    AuthService.role(actor, "buyer");
    auth.requireActiveSession(actor);
    require(store.byKey("wechat", ProviderSupport.env("PAW_WECHAT_MINI_APP_ID") + ":" + actor.id()) != null,
        403, "FORBIDDEN", "请先使用当前小程序的微信身份登录");
    boolean approved = new Input(body, "approved").bool("approved");
    String before = saved(ticket);
    var state = read(before);
    require("pending".equals(text(state, "status")), 409, "QR_ALREADY_USED", "二维码已处理，请回到电脑查看");
    state.put("status", approved ? "confirmed" : "rejected");
    if (approved) state.put("userId", actor.id());
    require(temp.replace("mini-qr:" + ticket, before, write(state)), 409, "QR_ALREADY_USED", "二维码已处理或过期");
    return map("status", state.get("status"));
  }

  @Transactional
  public Map<String, Object> poll(String ticket, Map<String, Object> body, String ip) {
    String browser = new Input(body, "browserToken").str("browserToken", 32, 128);
    temp.limit("mini-qr-poll:" + ip, 180, 60);
    String before = saved(ticket);
    var state = read(before);
    require(hash(browser).equals(text(state, "browser")), 403, "FORBIDDEN", "请在发起扫码的浏览器继续登录");
    if (!"confirmed".equals(text(state, "status"))) return map("status", state.get("status"));
    // 先原子领取再签发会话；并发请求或重放只能有一个成功。
    state.put("status", "consumed");
    require(temp.replace("mini-qr:" + ticket, before, write(state)), 409, "QR_ALREADY_USED", "登录结果已领取，请重新扫码");
    store.lock();
    var user = store.get("user", text(state, "userId"));
    require(user != null, 401, "UNAUTHORIZED", "账号不可用，请重新登录");
    return map("status", "authenticated", "login", auth.login(user, "buyer"), "returnPath", state.get("returnPath"));
  }

  public Map<String, Object> cancel(String ticket, Map<String, Object> body) {
    String browser = new Input(body, "browserToken").str("browserToken", 32, 128);
    String before = saved(ticket);
    var state = read(before);
    require(hash(browser).equals(text(state, "browser")), 403, "FORBIDDEN", "无操作权限");
    if (List.of("pending", "confirmed").contains(text(state, "status"))) {
      state.put("status", "cancelled");
      temp.replace("mini-qr:" + ticket, before, write(state));
    }
    return map("status", "cancelled");
  }

  /** 官方接口仅在服务端调用；token 按应用缓存，不将上游异常 URL 或密钥返回客户端。 */
  private String codeImage(String scene) {
    String app = ProviderSupport.env("PAW_WECHAT_MINI_APP_ID");
    String version = ProviderSupport.env("PAW_WECHAT_MINI_ENV_VERSION");
    if (version.isBlank()) version = "release";
    require(List.of("release", "trial", "develop").contains(version), 503, "SERVICE_UNAVAILABLE", "小程序版本配置异常");
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        String token = temp.get("mini-api-token:" + app);
        if (token == null) {
          var response = post("https://api.weixin.qq.com/cgi-bin/stable_token", map("grant_type", "client_credential",
              "appid", app, "secret", ProviderSupport.env("PAW_WECHAT_MINI_APP_SECRET")));
          require(response.statusCode() == 200, 502, "UPSTREAM_ERROR", "微信服务暂不可用");
          var result = read(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
          require(result.get("access_token") instanceof String value && !value.isBlank() && number(result, "expires_in") > 60,
              503, "SERVICE_UNAVAILABLE", "无法获取小程序接口凭据，请检查微信平台配置");
          token = text(result, "access_token");
          temp.put("mini-api-token:" + app, token, (int) Math.min(number(result, "expires_in") - 60, 7000));
        }
        var response = post("https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token=" + token,
            map("scene", scene, "page", "pages/web-login/index", "env_version", version,
                "check_path", "release".equals(version), "width", 280));
        byte[] bytes = response.body();
        // 只接受真实 PNG/JPEG；微信错误 JSON 不能伪装成可扫描图片。
        boolean png = bytes.length > 8 && bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71;
        boolean jpeg = bytes.length > 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255;
        if (response.statusCode() == 200 && (png || jpeg))
          return "data:image/" + (png ? "png" : "jpeg") + ";base64," + Base64.getEncoder().encodeToString(bytes);
        var error = read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        if (attempt == 0 && List.of(40001L, 40014L, 42001L).contains(number(error, "errcode"))) {
          temp.take("mini-api-token:" + app);
          continue;
        }
        throw new ApiException(502, "UPSTREAM_ERROR", "小程序码生成失败，请确认对应版本已上传且扫码页面可用");
      }
    } catch (ApiException e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    catch (Exception e) { /* 仅提供固定错误，避免泄露微信接口中的凭证。 */ }
    throw new ApiException(502, "UPSTREAM_ERROR", "微信服务暂不可用，请重试");
  }

  private HttpResponse<byte[]> post(String url, Map<String, Object> body) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(write(body))).build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }
}
