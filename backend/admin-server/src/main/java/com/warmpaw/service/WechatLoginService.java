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

/**
 * 网页授权 state 与浏览器 HttpOnly Cookie 绑定，openid 仅由微信服务端换取。
 *
 * <p><b>两套应用，别混用</b>：{@code mode=oauth} 是公众号网页授权（{@code snsapi_base}，
 * 在微信内打开），{@code mode=web} 是开放平台扫码（{@code snsapi_login}，桌面浏览器）。
 * 两者各有独立的 appId/secret/回调，环境变量前缀分别是 {@code PAW_WECHAT_} 与
 * {@code PAW_WECHAT_WEB_}。分开的原因很实际：同一个自然人在不同 appId 下拿到的是不同 openid，
 * 混用会直接串号。
 *
 * <p><b>不信任回调参数</b>：{@code code} 只能由服务端换 openid；{@code state} 是一次性票据，
 * 由服务端签发、300 秒过期，且与发起授权的浏览器哈希绑定（见 {@link #authorize}）。
 * 回调来自另一个浏览器（或被复制到别的设备）就无法完成登录——这是防登录 CSRF 的全部机制。
 *
 * <p><b>不合成账号</b>：网站扫码首次登录建一个无手机号账号（后续可绑）；
 * 公众号首次授权不直接建号，而是返回 {@code bind_required} + {@code bindTicket}，
 * 要求先走 {@link #bindPhone} 用手机号 + 短信验证码确认归属。
 *
 * <p><b>不使用 unionid</b>：身份统一按 {@code appId:openid} 存，同一自然人在公众号与小程序
 * 会被当成两个账号。打通需要另存 unionid，当前刻意没做——跨应用合并账号的规则一旦定错，
 * 事后拆不开。
 */
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

  /**
   * 环境变量前缀：两套应用互不共用。任何一处前缀拼错都会落到"未配置"（503），
   * 而不是悄悄借用另一套的凭据——这是刻意的失败方向。
   */
  private String prefix(String mode) {
    return "web".equals(mode) ? "PAW_WECHAT_WEB_" : "PAW_WECHAT_";
  }

  /** appId 只从进程环境读（{@code ProviderSupport.env}），不走配置文件，避免把应用标识与密钥写进仓库。 */
  private String app(String mode) {
    return ProviderSupport.env(prefix(mode) + "APP_ID");
  }

  /**
   * 只公开是否具备配置，不向浏览器返回应用密钥或回调内部信息。
   *
   * <p>未配置时调用方（{@link #authorize}）返回 503，而不是拼一个必然失败的跳转地址
   * ——后者会让用户看到微信的空白错误页，排查成本全落在用户身上。
   */
  public boolean configured() {
    return configured("oauth");
  }

  /** 网站扫码（开放平台）是否配置齐全；与公众号授权分开判断，两者可以只启用其一。 */
  public boolean qrConfigured() {
    return configured("web");
  }

  /**
   * 回调地址同样按 mode 取，但<b>变量名不同</b>：公众号是 {@code OAUTH_REDIRECT}，
   * 网站扫码是 {@code REDIRECT}。只看代码很容易以为是笔误，配置时务必两套都填。
   */
  private String callback(String mode) {
    return ProviderSupport.env(prefix(mode) + ("web".equals(mode) ? "REDIRECT" : "OAUTH_REDIRECT"));
  }

  /**
   * 配置完整性检查，四个条件缺一不可。
   *
   * <p>回调地址强制 {@code https}：微信只接受 https 回调，给 http 会让用户看到一个无法回传错误的
   * 错误页。同时禁止 {@code userinfo}（{@code https://user:pass@host} 形式）与 {@code fragment}
   * ——前者会把凭据写进跳转串，后者根本不会发送到服务端。
   *
   * <p>URI 解析失败（含非法字符）返回 false 而不是抛异常：配置错误应当表现为"功能未开放"，
   * 不能让整个 Bean 初始化失败、把服务拖住。
   */
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

  /**
   * 生成微信授权跳转地址，同时把本次授权上下文写进一次性 state。
   *
   * <p>入参约束：
   *
   * <ul>
   *   <li>{@code mode} 缺省 {@code oauth}；{@code web} 只能配 {@code scene=login}
   *       （网站扫码不承担支付授权）；
   *   <li>{@code scene=pay} 必须已登录买家，并在 state 里记下 {@code userId}，回调时要求是同一人，
   *       防止 A 发起的授权被 B 用来绑自己的微信；
   *   <li>{@code returnPath} 过 {@code safePath}，只允许站内相对路径，避免开放重定向。
   * </ul>
   *
   * <p>只有 {@code scene=login} 要图形验证码：支付授权本身有登录态保护，再加验证码只会打断
   * 下单流程；而登录入口是匿名的，必须挡住机器批量拉起授权（那会消耗公众号的调用额度）。
   *
   * <p>state 里存的是浏览器标识的<b>哈希</b>，不存原值——临时存储泄露也拿不到可用的 Cookie 值。
   * 有效期 300 秒，过期即无法回调，这既是安全窗口也是"扫码后别磨蹭"的提示。
   */
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

  /**
   * 授权回调：校验 state → 用 code 换 openid → 决定"直接登录 / 绑定手机号 / 绑定微信"。
   *
   * <p>顺序与失败语义：
   *
   * <ol>
   *   <li>{@code temp.take} 先取出 state，<b>一次性，无论后续是否通过校验都已作废</b>，
   *       重放同一个 state 一定失败——这就是防重放的全部机制；
   *   <li>浏览器哈希必须匹配，否则 400 {@code WECHAT_AUTH_INVALID}；
   *   <li>{@code mode}/{@code appId} 一律取自 state，而不是回调参数——否则可以拿一套配置的 state
   *       去换另一套应用的凭据。appId 与环境变量当前值不一致时要求重新扫码；
   *   <li>{@code scene=pay} 还要求当前登录者就是发起者。
   * </ol>
   *
   * <p>换 openid 的请求由服务端发出，要同时满足：HTTP 200、{@code openid} 是长度 ≤128 的非空串、
   * 且响应里没有 {@code errcode}。微信的业务错误也可能带 200，只看状态码会被骗过。
   *
   * <p>三种结局：
   *
   * <ul>
   *   <li>已绑定过 → 直接签发买家会话（{@code authenticated}）；
   *   <li>{@code mode=web} 首次扫码 → 建一个无手机号账号并绑定，直接登录；
   *   <li>{@code mode=oauth} 首次授权 → 只发 {@code bindTicket}（300 秒），
   *       等 {@link #bindPhone} 用手机号 + 短信验证码完成归属。
   * </ul>
   */
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

  /**
   * 建立双向绑定：{@code wechat(appId:userId)} 与 {@code wechat_openid(appId:openid)} 各一行。
   *
   * <p>两张表都写，是因为两个方向的查询都要 O(1)：登录时按 openid 找账号，绑定时按账号找 openid。
   * 冲突检查也是双向的——任何一个方向已经指向别人就 409 {@code WECHAT_ALREADY_BOUND}，
   * 防止"一个微信号绑多个账号"或"一个账号绑多个微信号"。
   *
   * <p>已存在且一致时什么都不做（幂等），所以重复绑定既不报错也不会产生第二行。
   */
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

  /**
   * 公众号首次授权的第二步：用手机号 + 短信验证码完成绑定并登录。
   *
   * <p>{@code bindTicket} 是 {@link #login} 发出的临时凭据（300 秒），这里先 {@code get} 只读校验，
   * 真正消费在最后一步 {@code take}。中间 {@code checkSms} 的 {@code scope} 传的就是这张票据：
   * 于是验证码与这次授权绑定，登录验证码不能拿来绑微信，A 的票据也不能配 B 的验证码。
   *
   * <p>手机号已存在就登进已有账号、不存在则建新号，然后绑定微信。若该手机号账号已经绑过别的
   * 微信，{@link #bind} 会 409——这里不自动解绑，账号归属变更必须走人工。
   */
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

  /** URL 编码：appId / secret / 回调 / code 都可能含 {@code + / = &}，少编一个就会把查询串截断。 */
  private String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
