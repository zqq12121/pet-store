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

/**
 * 小程序扫码只传短期场景号；浏览器独立持有领取凭据，手机不能领取网站令牌。
 *
 * <p><b>两端分工</b>：桌面浏览器调 {@link #create} 拿到 {@code ticket}（进二维码）与
 * {@code browserToken}（只留在浏览器）；手机用小程序扫码后调 {@link #inspect} 看状态、
 * {@link #confirm} 确认；浏览器再用 {@code browserToken} 轮询 {@link #poll} 领取会话。
 * {@code browserToken} 从不进入二维码或图片 URL，所以<b>手机端拿不到、也领不走网站会话</b>。
 *
 * <p><b>票据生命周期</b>：{@code pending → confirmed/rejected → consumed}（或 {@code cancelled}），
 * 存在 {@link TemporaryStore} 里 300 秒。所有状态推进都用 {@code replace(期望值, 新值)} 做 CAS，
 * 并发确认、并发轮询只会有一次成功；{@code replace} 保留原 TTL，写状态不会给票据续命。
 *
 * <p><b>防重放</b>：{@link #poll} 先把状态推进到 {@code consumed}，<b>再</b>签发会话。顺序不能反
 * ——反了两个并发请求会各自拿到一个有效 token。{@link #confirm} 同理只接受 {@code pending}。
 *
 * <p><b>6 位校验码不是凭据</b>：{@code create} 给浏览器一个 {@code verificationCode}，
 * 手机端 {@code inspect} 能读到同一个码，供用户比对"手机上确认的是不是电脑上这张码"，
 * 防止扫到被替换的二维码。它不能换会话，泄露了也无法登录。
 *
 * <p>扫码落地页固定为 {@code pages/web-login/index}；场景号是 32 位十六进制
 * （{@code getwxacodeunlimit} 的 scene 上限 32 字符，UUID 去掉连字符刚好 32 位）。
 */
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

  /** 小程序 appId/secret 是否齐备；缺一个就 503，没必要生成一张扫了也没用的二维码。 */
  public boolean configured() {
    return !ProviderSupport.env("PAW_WECHAT_MINI_APP_ID").isBlank()
        && !ProviderSupport.env("PAW_WECHAT_MINI_APP_SECRET").isBlank();
  }

  /**
   * 生成扫码票据与小程序码。
   *
   * <p>校验顺序：{@code returnPath} 过 {@code safePath}（只允许站内相对路径）→ 判配置 →
   * 按 IP 限流 10 次/分钟 → 最后才是图形验证码。验证码最贵，放最后，让明显的非法请求尽早失败。
   *
   * <p>{@code ticket} 用 UUID 去连字符（32 位十六进制），不是为了安全，而是为了满足微信 scene
   * 的 32 字符上限；真正的凭据是 {@code browserToken}（32 字节随机），库里只存它的哈希。
   * {@code appId} 一并写进状态，用来发现"扫码期间换了小程序配置"。
   *
   * <p>返回的 {@code image} 是 data URI，不落盘——二维码只活 5 分钟，没必要占存储。
   */
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

  /**
   * 读取票据并做三重校验：格式、过期、appId 未变更。
   *
   * <p>格式用 {@code [a-f0-9]{32}} 严格匹配：既保证与生成规则一致，也避免把任意字符串拼进
   * 临时存储的键（虽然只是内存/Redis 键，但没必要留这个口子）。过期返回 410
   * {@code QR_EXPIRED}（而不是 404），语义是"曾经有效"，前端据此提示"回电脑刷新"。
   *
   * <p>appId 变更同样返回 410 而不是继续用旧状态：旧码对应的用户属于另一个小程序，放过去就是串号。
   */
  private String saved(String ticket) {
    require(ticket.matches("[a-f0-9]{32}"), 400, "VALIDATION_ERROR", "扫码场景无效");
    String value = temp.get("mini-qr:" + ticket);
    require(value != null && Instant.parse(text(read(value), "expiresAt")).isAfter(Instant.now()),
        410, "QR_EXPIRED", "二维码已过期，请在电脑上刷新");
    require(ProviderSupport.env("PAW_WECHAT_MINI_APP_ID").equals(text(read(value), "appId")),
        410, "QR_EXPIRED", "登录配置已变更，请重新扫码");
    return value;
  }

  /**
   * 小程序端查询票据状态（公开，凭 ticket 即可）。
   *
   * <p>只返回 {@code status/verificationCode/expiresAt}，<b>不返回 userId 或任何会话</b>——
   * ticket 就在二维码里，扫到的人本来就能看到这些；会话必须回到发起扫码的浏览器领取。
   *
   * <p>限流 60 次/分钟/IP：够小程序轮询用，但挡得住暴力枚举 32 位十六进制票据。
   */
  public Map<String, Object> inspect(String ticket, String ip) {
    temp.limit("mini-qr-inspect:" + ip, 60, 60);
    var state = read(saved(ticket));
    return map("status", state.get("status"), "verificationCode", state.get("code"), "expiresAt", state.get("expiresAt"));
  }

  /**
   * 手机端确认或拒绝本次登录。
   *
   * <p>四道闸，缺一个都能被绕过：
   *
   * <ol>
   *   <li>必须是买家会话（店长令牌 403），并复核会话此刻仍然有效（可能刚被登出或改密）；
   *   <li>该账号必须<b>已经用当前小程序登录过</b>（{@code wechat} 表里有 {@code appId:userId}），
   *       否则一个纯网页账号就能替任何扫码确认，"手机持有人"这个前提直接失效；
   *   <li>票据必须仍是 {@code pending}，否则 409 {@code QR_ALREADY_USED}；
   *   <li>状态推进用 CAS，输给并发请求时同样返回 409。
   * </ol>
   *
   * <p>拒绝时只记 {@code rejected}、不写 userId：被拒绝的票除了"被拒过"之外不该留下更多信息。
   */
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

  /**
   * 浏览器轮询并领取会话。
   *
   * <p>{@code browserToken} 的哈希必须与票据里记录的一致，否则 403——这是阻止手机端
   * （或任何只拿到 ticket 的人）直接领走网站会话的唯一屏障。
   *
   * <p>未确认时只回状态，让前端据 {@code rejected}/{@code cancelled} 停止轮询。已确认时
   * 先 CAS 到 {@code consumed} 再签会话：两个并发请求同时读到 {@code confirmed} 时，
   * 后到的那个会拿到 409；更晚的重放只会看到 {@code status=consumed}，不会再签发第二个 token。
   *
   * <p>签会话前再查一次 {@code user} 是否存在（401）：票据 5 分钟的有效期内，账号可能已被清理。
   * 顺带把 {@code returnPath} 回传，前端据此跳回原页面，不必在本地另存一份。
   */
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

  /**
   * 浏览器取消本次扫码（关页面、刷新、超时前主动放弃）。
   *
   * <p>只把 {@code pending}/{@code confirmed} 转成 {@code cancelled}：已经 {@code consumed} 说明
   * 会话已经签发，改票据也撤不回 token；{@code rejected} 本身已是终态。
   *
   * <p>返回值是固定字符串而非实际状态，所以对任何状态调用都"成功"——这是刻意做成幂等的清理动作，
   * 没必要为"其实没改"报错。{@code replace} 的结果也刻意不检查：并发取消时让另一端赢即可。
   */
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

  /**
   * 官方接口仅在服务端调用；token 按应用缓存，不将上游异常 URL 或密钥返回客户端。
   *
   * <p>access_token 走 {@code stable_token} 接口（普通 token 接口在并发刷新时会互相顶掉），
   * 缓存在临时存储里，TTL 取 {@code expires_in - 60}（留一分钟余量）并硬压到 7000 秒以内；
   * 缓存键按 appId 区分，多应用共存时不会互相覆盖。
   *
   * <p>生成失败且错误码属于 token 类（40001/40014/42001）时，清缓存重试<b>一次</b>——
   * 这是 token 被别处提前失效时最常见的自愈路径，重试次数写死不做循环，避免在故障时放大调用量。
   *
   * <p>响应只接受真实 PNG/JPEG 魔数：微信参数错误时会返回 HTTP 200 的 JSON，
   * 不判别就会把一段错误文本 base64 成"图片"发给前端，用户扫半天扫不出来。
   *
   * <p>所有异常都收敛成固定文案，不透传上游响应——里面可能带着 access_token。
   */
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

  /** 统一的 JSON POST：15 秒超时，返回原始字节——二维码是二进制，按字符串读会被编码破坏。 */
  private HttpResponse<byte[]> post(String url, Map<String, Object> body) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(write(body))).build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }
}
