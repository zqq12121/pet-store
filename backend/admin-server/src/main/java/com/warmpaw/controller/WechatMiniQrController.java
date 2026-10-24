package com.warmpaw.controller;

import static com.warmpaw.controller.support.ApiResponses.ok;
import com.warmpaw.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * 小程序扫码登录（PC 浏览器 + 手机小程序两端协作）。
 *
 * <p>扫码与网站领取分离；含短期凭据的响应一律禁止缓存（{@link CacheControl#noStore()}，
 * 因为 ticket/browserToken/会话令牌都不能留在浏览器或中间代理里）。
 *
 * <p>路由前缀 {@code /api/v1/auth/mini-wechat/qr}。<b>五个接口的鉴权并不统一</b>：
 *
 * <ul>
 *   <li>{@code create}、{@code inspect}、{@code poll}、{@code cancel} 都是公开入口，
 *       靠图形验证码、IP 限流与 {@code browserToken} 的哈希比对自保；
 *   <li>{@code confirm} 唯一接受 Bearer 令牌：{@code auth.identify(...)} 可能返回 null，
 *       但 {@link com.warmpaw.service.WechatMiniQrService#confirm} 内立刻
 *       {@code role(actor, "buyer")}，因此<b>实际要求买家登录</b>，并要求该买家已绑定当前小程序。
 * </ul>
 *
 * <p>委托 {@code WechatMiniQrService}：状态机 {@code pending → confirmed → consumed}
 * （或 {@code rejected}/{@code cancelled}）、一次性 code、过期判断都在那里。
 */
@RestController
@RequestMapping("/api/v1/auth/mini-wechat/qr")
public class WechatMiniQrController {
  private final WechatMiniQrService qr;
  private final AuthService auth;
  public WechatMiniQrController(WechatMiniQrService qr, AuthService auth) { this.qr = qr; this.auth = auth; }

  /**
   * 统一包装响应并强制 {@code Cache-Control: no-store}。
   *
   * <p>所有扫码响应都含二维码场景号、浏览器凭据或会话令牌，一旦被缓存，
   * 同一台设备上的后续使用者可能读到上一次登录的凭据。
   */
  private ResponseEntity<Object> response(Object data) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ok(200, data).getBody());
  }
  /**
   * 发起扫码登录：生成二维码场景与浏览器凭据。
   *
   * <p>POST {@code /auth/mini-wechat/qr}，公开。字段 {@code returnPath}（站内相对路径，≤500）
   * 与图形验证码字段；同 IP 每分钟最多 10 次，未配置小程序 503。
   *
   * <p>返回的 {@code ticket} 是二维码里的场景号（32 位十六进制），{@code browserToken} 只下发给
   * 发起扫码的浏览器，<b>不会进入二维码或图片 URL</b>——手机端无法据此领取网站会话。
   * 有效期 5 分钟。
   *
   * <p>非幂等：每次调用都新建一条扫码状态。
   *
   * @return 200 与 {@code ticket/browserToken/image/verificationCode/expiresIn}
   */
  @PostMapping
  public ResponseEntity<Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    return response(qr.create(body, req.getRemoteAddr()));
  }
  /**
   * 查询二维码状态（小程序轮询用，公开）。
   *
   * <p>GET {@code /auth/mini-wechat/qr/{ticket}}。ticket 必须匹配 32 位十六进制格式（否则 400），
   * 已过期或小程序 appId 配置变更返回 410 {@code QR_EXPIRED}。同 IP 每分钟最多 60 次。
   *
   * <p>只返回 {@code status}、{@code verificationCode}（供用户核对电脑与手机是同一张码）
   * 与 {@code expiresAt}，<b>不返回 userId 或任何会话</b>——领取会话必须回到发起扫码的浏览器。
   * 只读、幂等。
   */
  @GetMapping("/{ticket}")
  public ResponseEntity<Object> inspect(@PathVariable String ticket, HttpServletRequest req) {
    return response(qr.inspect(ticket, req.getRemoteAddr()));
  }
  /**
   * 手机端确认/拒绝本次扫码登录。
   *
   * <p>POST {@code /auth/mini-wechat/qr/{ticket}/confirm}。<b>鉴权：买家令牌</b>——
   * Controller 允许 {@code identify} 返回 null，但 Service 立即执行
   * {@code role(actor, "buyer")}（401）与 {@code requireActiveSession}（会话被撤销时 401），
   * 因此匿名调用必定失败。还要求该买家已用本小程序登录过（否则 403），
   * 防止网页端随便拿一个买家令牌代人确认。
   *
   * <p>body 只有 {@code approved}（布尔）：true 置 {@code confirmed}，false 置 {@code rejected}。
   * 只有 {@code pending} 可被处理，重复确认 409 {@code QR_ALREADY_USED}。
   *
   * <p>非幂等：状态迁移一次即定，写入用 compare-and-set，并发只有一个成功。
   *
   * @return 200 与最终 {@code status}
   */
  @PostMapping("/{ticket}/confirm")
  public ResponseEntity<Object> confirm(@PathVariable String ticket, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    return response(qr.confirm(ticket, body, auth.identify(req.getHeader("Authorization"))));
  }
  /**
   * 浏览器轮询并领取登录结果。
   *
   * <p>POST {@code /auth/mini-wechat/qr/{ticket}/poll}，公开，但 body 必须带 {@code browserToken}
   * （32~128 位），其哈希要与创建时保存的一致，否则 403——这是"只有发起扫码的那台浏览器
   * 能拿到会话"的唯一保障。同 IP 每分钟最多 180 次。
   *
   * <p>状态未到 {@code confirmed} 时只回状态；到了则先原子置 {@code consumed} 再签发买家会话，
   * 因此<b>同一 ticket 的会话只能被领取一次</b>：并发或重放返回 409 {@code QR_ALREADY_USED}。
   * 账号不可用时 401。返回还带 {@code returnPath}，用于跳回发起登录前的页面。
   *
   * @return 200 与 {@code status=authenticated}、{@code login} 会话对象、{@code returnPath}
   */
  @PostMapping("/{ticket}/poll")
  public ResponseEntity<Object> poll(@PathVariable String ticket, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    return response(qr.poll(ticket, body, req.getRemoteAddr()));
  }
  /**
   * 取消本次扫码登录（浏览器主动放弃）。
   *
   * <p>POST {@code /auth/mini-wechat/qr/{ticket}/cancel}，公开，靠 {@code browserToken}
   * 的哈希比对确认调用方是发起方（不匹配 403）。只有 {@code pending}/{@code confirmed}
   * 会被置为 {@code cancelled}；已 {@code consumed} 或已过期则原样返回，不报错。
   *
   * <p>幂等：重复取消结果一致（始终回 {@code status=cancelled}），调用方无需区分首次与重试。
   *
   * @return 200 与 {@code status=cancelled}
   */
  @PostMapping("/{ticket}/cancel")
  public ResponseEntity<Object> cancel(@PathVariable String ticket, @RequestBody Map<String, Object> body) {
    return response(qr.cancel(ticket, body));
  }
}
