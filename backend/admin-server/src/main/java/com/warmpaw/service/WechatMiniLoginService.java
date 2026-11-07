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

/**
 * 小程序 code 只在服务端换取身份；不要求手机号，不向客户端返回微信会话密钥。
 *
 * <p><b>为什么 code 不能当用户标识</b>：{@code wx.login} 拿到的 code 一次性、几分钟就失效，
 * 真正稳定的是 {@code openid}。所以必须由服务端调 {@code jscode2session} 换出 openid，
 * 客户端既看不到也伪造不了。
 *
 * <p><b>session_key 怎么处理</b>：只在响应里校验"存在且非空"，<b>不落库、不返回</b>。
 * 本服务不解密敏感数据（手机号走短信绑定），保留 key 没有用途；不保存也就没有
 * "密钥泄露后可解密历史数据"的风险。
 *
 * <p><b>账号归属</b>：身份键是 {@code appId:openid}。同一微信在不同小程序/公众号下 openid 不同，
 * 所以这里不认"同一个人"，也不使用 unionid 做跨应用合并。
 *
 * <p>小程序没有浏览器 Cookie 可以绑定，也不需要 state；靠 IP 限流与微信侧一次性 code 本身防刷。
 */
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

  /**
   * 小程序登录（静默）：code → openid → 复用或新建买家账号 → 签发会话。
   *
   * <p>四段结构：
   *
   * <ol>
   *   <li>先限流（同 IP 每分钟 30 次）再判配置，配置不全返回 503——明确告知"没开通"，
   *       而不是让用户对着"登录失败"反复重试；
   *   <li>调 {@code jscode2session}，把上游错误码翻译成本地语义（见下）；
   *   <li>加全局锁后按 {@code appId:openid} 查身份，找不到才建号；建号与两张绑定表在
   *       同一事务内完成，不会留下"有账号没绑定"的中间态；
   *   <li>签发会话并附 {@code isNewUser}，前端据此决定要不要引导完善资料。
   * </ol>
   *
   * <p><b>错误码映射</b>（微信业务错误同样返回 HTTP 200，必须解析 body）：
   * {@code 40029/40163} code 无效或已用 → 401，前端重新 {@code wx.login}；
   * {@code 40226} 被风控 → 401 但换一个错误码，提示"稍后再试"而非"重新登录"；
   * {@code 45011} 频率限制 → 429；{@code 40013/40125} appId/secret 配错 → 503（运维问题，
   * 让用户重试没有意义）；其余 → 502。
   *
   * <p>所有异常都不透传上游 URL 与响应体：URL 里带着 secret 和 code，把异常信息写进日志
   * 或响应等于主动泄露。
   */
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
    var login = auth.login(user, "buyer", AuthService.MINI_PROGRAM_SECONDS);
    login.put("isNewUser", isNewUser);
    return login;
  }

  /** URL 编码 code 与凭据；code 可能含 {@code + / =}，不编码会在上游被截断成无效凭证。 */
  private String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
