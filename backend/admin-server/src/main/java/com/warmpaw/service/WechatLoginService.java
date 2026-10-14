package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 网页授权 state 与浏览器 HttpOnly Cookie 绑定，openid 仅由微信服务端换取。 */
@Service
public class WechatLoginService {
  private final BusinessRepository store;
  private final TemporaryStore temp;
  private final AuthService auth;
  private final GraphCaptchaService graphCaptcha;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public WechatLoginService(BusinessRepository store, TemporaryStore temp, AuthService auth, GraphCaptchaService graphCaptcha) {
    this.store = store;
    this.temp = temp;
    this.auth = auth;
    this.graphCaptcha = graphCaptcha;
  }

  private String prefix(String mode) {
    return "web".equals(mode) ? "PAW_WECHAT_WEB_" : "PAW_WECHAT_";
  }

  private String app(String mode) {
    return ProviderSupport.env(prefix(mode) + "APP_ID");
  }

  /** 只公开是否具备配置，不向浏览器返回应用密钥或回调内部信息。 */
  public boolean configured() {
    return configured("oauth");
  }

  public boolean qrConfigured() {
    return configured("web");
  }

  private String callback(String mode) {
    return ProviderSupport.env(prefix(mode) + ("web".equals(mode) ? "REDIRECT" : "OAUTH_REDIRECT"));
  }

  private boolean configured(String mode) {
    try {
      URI uri = URI.create(callback(mode));
      return !app(mode).isBlank()
          && !ProviderSupport.env(prefix(mode) + "APP_SECRET").isBlank()
          && "https".equals(uri.getScheme()) && uri.getHost() != null
          && uri.getUserInfo() == null && uri.getFragment() == null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public Map<String, Object> authorize(
      Map<String, Object> query, AuthService.Actor actor, String browser) {
    Input in = new Input(query, "mode,scene,returnPath," + GraphCaptchaService.FIELDS);
    String mode = in.has("mode") ? in.choice("mode", "oauth,web") : "oauth";
    String scene = in.choice("scene", "login,pay"), path = in.str("returnPath", 1, 500);
    com.warmpaw.common.ProviderSupport.safePath(path);
    require(!"web".equals(mode) || "login".equals(scene), 400, "VALIDATION_ERROR", "网站扫码仅用于登录");
    if (scene.equals("pay")) AuthService.role(actor, "buyer");
    String callback = callback(mode), appId = app(mode);
    require(
        configured(mode),
        503,
        "SERVICE_UNAVAILABLE",
        "web".equals(mode) ? "微信扫码登录尚未配置" : "微信网页授权尚未配置");
    // 登录授权必须先通过图形验证；回调沿用一次性 state 与浏览器 Cookie 绑定。
    if (scene.equals("login")) graphCaptcha.verify(query);
    String state = auth.randomToken();
    temp.put(
        "oauth:" + state,
        write(
            map(
                "browser",
                hash(browser),
                "scene",
                scene,
                "mode",
                mode,
                "appId",
                appId,
                "userId",
                actor == null ? null : actor.id(),
                "returnPath",
                path)),
        300);
    return map(
        "authorizeUrl",
        ("web".equals(mode) ? "https://open.weixin.qq.com/connect/qrconnect?appid="
            : "https://open.weixin.qq.com/connect/oauth2/authorize?appid=")
            + enc(appId)
            + "&redirect_uri="
            + enc(callback)
            + "&response_type=code&scope=" + ("web".equals(mode) ? "snsapi_login" : "snsapi_base") + "&state="
            + state
            + "#wechat_redirect",
        "expiresIn",
        300);
  }

  @Transactional
  public Map<String, Object> login(
      Map<String, Object> body, AuthService.Actor actor, String browser) {
    Input in = new Input(body, "code,state");
    String state = in.str("state", 1, 200), saved = temp.take("oauth:" + state);
    require(
        saved != null && browser != null && hash(browser).equals(text(read(saved), "browser")),
        400,
        "WECHAT_AUTH_INVALID",
        "微信授权状态无效");
    Map<String, Object> context = read(saved);
    // 凭据类型和应用归属取自服务端 state，不能由回调请求切换。
    String mode = text(context, "mode"), appId = text(context, "appId");
    require(appId.equals(app(mode)), 400, "WECHAT_AUTH_INVALID", "微信应用配置已变更，请重新扫码");
    boolean pay = "pay".equals(text(context, "scene"));
    if (pay) {
      AuthService.role(actor, "buyer");
      require(actor.id().equals(text(context, "userId")), 400, "WECHAT_AUTH_INVALID", "发起授权的账号不一致");
    }
    String secret = ProviderSupport.env(prefix(mode) + "APP_SECRET");
    require(!secret.isBlank(), 503, "SERVICE_UNAVAILABLE", "微信授权密钥尚未配置");
    Map<String, Object> result;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      "https://api.weixin.qq.com/sns/oauth2/access_token?appid="
                          + enc(appId)
                          + "&secret="
                          + enc(secret)
                          + "&code="
                          + enc(in.str("code", 1, 500))
                          + "&grant_type=authorization_code"))
              .timeout(Duration.ofSeconds(15))
              .GET()
              .build();
      HttpResponse<String> r =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      result = read(r.body());
      require(
          r.statusCode() == 200
              && result.get("openid") instanceof String id && !id.isBlank() && id.length() <= 128
              && !result.containsKey("errcode"),
          400,
          "WECHAT_AUTH_INVALID",
          "微信授权码无效");
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(502, "UPSTREAM_ERROR", "微信授权服务暂不可用");
    } catch (Exception e) {
      throw new ApiException(502, "UPSTREAM_ERROR", "微信授权服务暂不可用");
    }
    String openid = text(result, "openid");
    store.lock();
    Map<String, Object> identity = store.byKey("wechat_openid", appId + ":" + openid);
    if (pay) {
      require(
          identity == null || actor.id().equals(text(identity, "ownerId")),
          409,
          "WECHAT_ALREADY_BOUND",
          "微信已绑定其他账号");
      bind(actor.id(), appId, openid);
      return map("status", "wechat_ready");
    }
    if (identity != null)
      return map(
          "status",
          "authenticated",
          "login",
          auth.login(store.get("user", text(identity, "ownerId")), "buyer"));
    if ("web".equals(mode)) {
      // 网站首次扫码直接建号；不读取手机号，也不按昵称合并其他账号。
      var user = store.create("user", null, null, map("phone", null, "nickname", "微信用户", "avatarUrl", null));
      bind(text(user, "id"), appId, openid);
      return map("status", "authenticated", "login", auth.login(user, "buyer"));
    }
    String ticket = auth.randomToken();
    temp.put("bind:" + ticket, write(map("openid", openid, "appId", appId)), 300);
    return map("status", "bind_required", "bindTicket", ticket, "expiresIn", 300);
  }

  private void bind(String user, String appId, String openid) {
    Map<String, Object> existing = store.byKey("wechat", appId + ":" + user),
        identity = store.byKey("wechat_openid", appId + ":" + openid);
    require(
        (existing == null || openid.equals(text(existing, "openid")))
            && (identity == null || user.equals(text(identity, "ownerId"))),
        409,
        "WECHAT_ALREADY_BOUND",
        "微信身份已绑定其他账号");
    if (existing == null)
      store.create("wechat", user, appId + ":" + user, map("openid", openid, "appId", appId));
    if (identity == null)
      store.create("wechat_openid", user, appId + ":" + openid, map("appId", appId));
  }

  @Transactional
  public Map<String, Object> bindPhone(Map<String, Object> body) {
    Input in = new Input(body, "bindTicket,phone,smsRequestId,smsCode");
    String ticket = in.str("bindTicket", 1, 200),
        phone = in.phone("phone"),
        saved = temp.get("bind:" + ticket);
    require(saved != null, 400, "WECHAT_AUTH_INVALID", "绑定凭证无效");
    auth.checkSms(
        in.str("smsRequestId", 1, 64), in.str("smsCode", 6, 6), phone, "wechat_bind", ticket);
    store.lock();
    Map<String, Object> user = store.byKey("user", phone);
    if (user == null)
      user =
          store.create(
              "user", null, phone, map("phone", phone, "nickname", "新用户", "avatarUrl", null));
    bind(text(user, "id"), text(read(saved), "appId"), text(read(saved), "openid"));
    temp.take("bind:" + ticket);
    return auth.login(user, "buyer");
  }

  private String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
