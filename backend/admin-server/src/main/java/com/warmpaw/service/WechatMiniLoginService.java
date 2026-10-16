package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 小程序 code 只在服务端换取身份；不要求手机号，不向客户端返回微信会话密钥。 */
@Service
public class WechatMiniLoginService {
  private final BusinessRepository store;
  private final TemporaryStore temp;
  private final AuthService auth;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public WechatMiniLoginService(BusinessRepository store, TemporaryStore temp, AuthService auth) {
    this.store = store;
    this.temp = temp;
    this.auth = auth;
  }

  @Transactional
  public Map<String, Object> login(Map<String, Object> body, String ip) {
    String code = new Input(body, "code").str("code", 1, 512);
    temp.limit("mini-wechat-login:" + ip, 30, 60);
    // 小程序与网页 OAuth 使用独立配置，避免误用公众号密钥。
    String appId = ProviderSupport.env("PAW_WECHAT_MINI_APP_ID");
    String secret = ProviderSupport.env("PAW_WECHAT_MINI_APP_SECRET");
    require(!appId.isBlank() && !secret.isBlank(), 503, "SERVICE_UNAVAILABLE", "小程序微信登录尚未配置");
    Map<String, Object> result;
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(
          "https://api.weixin.qq.com/sns/jscode2session?appid=" + enc(appId)
          + "&secret=" + enc(secret) + "&js_code=" + enc(code) + "&grant_type=authorization_code"))
          .timeout(Duration.ofSeconds(15)).GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      require(response.statusCode() == 200, 502, "UPSTREAM_ERROR", "微信登录服务暂不可用");
      result = read(response.body());
      // 微信业务错误也会返回 HTTP 200；区分凭证、频率和配置问题，不暴露上游明细。
      long error = number(result, "errcode");
      if (error != 0) {
        if (error == 40029 || error == 40163)
          throw new ApiException(401, "WECHAT_AUTH_INVALID", "微信登录凭证已失效，请重试");
        if (error == 40226)
          throw new ApiException(401, "WECHAT_AUTH_REJECTED", "当前微信登录受限，请稍后重试");
        if (error == 45011)
          throw new ApiException(429, "RATE_LIMITED", "微信登录过于频繁，请稍后再试");
        if (error == 40013 || error == 40125)
          throw new ApiException(503, "SERVICE_UNAVAILABLE", "小程序微信登录配置异常，请联系维护者");
        throw new ApiException(502, "UPSTREAM_ERROR", "微信登录服务暂不可用");
      }
      require(result.get("openid") instanceof String openid && !openid.isBlank() && openid.length() <= 128
          && result.get("session_key") instanceof String key && !key.isBlank(),
          401, "WECHAT_AUTH_INVALID", "微信登录凭证已失效，请重试");
    } catch (ApiException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(502, "UPSTREAM_ERROR", "微信登录服务暂不可用");
    } catch (Exception e) {
      // 不传播上游异常或请求 URL，避免泄露 secret、code、session_key。
      throw new ApiException(502, "UPSTREAM_ERROR", "微信登录服务暂不可用");
    }
    String openid = text(result, "openid"), identityKey = appId + ":" + openid;
    store.lock();
    Map<String, Object> identity = store.byKey("wechat_openid", identityKey);
    Map<String, Object> user;
    boolean isNewUser = identity == null;
    if (isNewUser) {
      // 首次验证通过才自动建号，账号及微信绑定在同一事务内保存。
      user = store.create("user", null, null, map("phone", null, "nickname", "微信用户", "avatarUrl", null));
      String userId = text(user, "id");
      store.create("wechat", userId, appId + ":" + userId, map("appId", appId, "openid", openid));
      store.create("wechat_openid", userId, identityKey, map("appId", appId));
    } else {
      // 同一应用、同一微信身份复用账号；一次性 code 不能作为用户标识。
      user = store.get("user", text(identity, "ownerId"));
    }
    var login = auth.login(user, "buyer");
    login.put("isNewUser", isNewUser);
    return login;
  }

  private String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
