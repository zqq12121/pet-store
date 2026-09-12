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

  public WechatLoginService(BusinessRepository store, TemporaryStore temp, AuthService auth) {
    this.store = store;
    this.temp = temp;
    this.auth = auth;
  }

  private String app() {
    return com.warmpaw.common.ProviderSupport.env("PAW_WECHAT_APP_ID");
  }

  public Map<String, Object> authorize(
      Map<String, Object> query, AuthService.Actor actor, String browser) {
    Input in = new Input(query, "scene,returnPath");
    String scene = in.choice("scene", "login,pay"), path = in.str("returnPath", 1, 500);
    com.warmpaw.common.ProviderSupport.safePath(path);
    if (scene.equals("pay")) AuthService.role(actor, "buyer");
    String callback = com.warmpaw.common.ProviderSupport.env("PAW_WECHAT_OAUTH_REDIRECT");
    require(
        !app().isBlank() && callback.startsWith("https://"),
        503,
        "SERVICE_UNAVAILABLE",
        "微信网页授权尚未配置");
    String state = auth.randomToken();
    temp.put(
        "oauth:" + state,
        write(
            map(
                "browser",
                hash(browser),
                "scene",
                scene,
                "userId",
                actor == null ? null : actor.id(),
                "returnPath",
                path)),
        300);
    return map(
        "authorizeUrl",
        "https://open.weixin.qq.com/connect/oauth2/authorize?appid="
            + enc(app())
            + "&redirect_uri="
            + enc(callback)
            + "&response_type=code&scope=snsapi_base&state="
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
    boolean pay = "pay".equals(text(context, "scene"));
    if (pay) {
      AuthService.role(actor, "buyer");
      require(actor.id().equals(text(context, "userId")), 400, "WECHAT_AUTH_INVALID", "发起授权的账号不一致");
    }
    String secret = com.warmpaw.common.ProviderSupport.env("PAW_WECHAT_APP_SECRET");
    require(!secret.isBlank(), 503, "SERVICE_UNAVAILABLE", "微信授权密钥尚未配置");
    Map<String, Object> result;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      "https://api.weixin.qq.com/sns/oauth2/access_token?appid="
                          + enc(app())
                          + "&secret="
                          + enc(secret)
                          + "&code="
                          + enc(in.str("code", 1, 500))
                          + "&grant_type=authorization_code"))
              .timeout(Duration.ofSeconds(15))
              .GET()
              .build();
      HttpResponse<String> r =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(10))
              .build()
              .send(request, HttpResponse.BodyHandlers.ofString());
      result = read(r.body());
      require(
          r.statusCode() == 200
              && result.get("openid") instanceof String
              && !result.containsKey("errcode"),
          400,
          "WECHAT_AUTH_INVALID",
          "微信授权码无效");
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(502, "UPSTREAM_ERROR", "微信授权服务暂不可用");
    }
    String openid = text(result, "openid");
    store.lock();
    Map<String, Object> identity = store.byKey("wechat_openid", app() + ":" + openid);
    if (pay) {
      require(
          identity == null || actor.id().equals(text(identity, "ownerId")),
          409,
          "WECHAT_ALREADY_BOUND",
          "微信已绑定其他账号");
      bind(actor.id(), openid);
      return map("status", "wechat_ready");
    }
    if (identity != null)
      return map(
          "status",
          "authenticated",
          "login",
          auth.login(store.get("user", text(identity, "ownerId")), "buyer"));
    String ticket = auth.randomToken();
    temp.put("bind:" + ticket, write(map("openid", openid, "appId", app())), 300);
    return map("status", "bind_required", "bindTicket", ticket, "expiresIn", 300);
  }

  private void bind(String user, String openid) {
    Map<String, Object> existing = store.byKey("wechat", app() + ":" + user),
        identity = store.byKey("wechat_openid", app() + ":" + openid);
    require(
        (existing == null || openid.equals(text(existing, "openid")))
            && (identity == null || user.equals(text(identity, "ownerId"))),
        409,
        "WECHAT_ALREADY_BOUND",
        "微信身份已绑定其他账号");
    if (existing == null)
      store.create("wechat", user, app() + ":" + user, map("openid", openid, "appId", app()));
    if (identity == null)
      store.create("wechat_openid", user, app() + ":" + openid, map("appId", app()));
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
    bind(text(user, "id"), text(read(saved), "openid"));
    temp.take("bind:" + ticket);
    return auth.login(user, "buyer");
  }

  private String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
