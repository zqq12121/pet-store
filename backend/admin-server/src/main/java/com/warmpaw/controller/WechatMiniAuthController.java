package com.warmpaw.controller;

import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.service.WechatMiniLoginService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 原生小程序登录入口，身份只能由微信服务端校验（调用方：微信小程序，公开无鉴权）。
 *
 * <p>路由前缀 {@code /api/v1/auth/mini-wechat}。这是纯公开入口：不读 {@code Authorization}，
 * 也不存在"用已有令牌换绑"的语义——身份唯一来源是微信下发的临时 {@code code}，
 * 由 {@link WechatMiniLoginService} 在服务端换取 openid，客户端永远拿不到 session_key。
 *
 * <p>委托 {@link WechatMiniLoginService}：限流（同 IP 每分钟 30 次）、微信错误码映射、
 * 账号创建与绑定都在那里完成。
 */
@RestController
@RequestMapping("/api/v1/auth/mini-wechat")
public class WechatMiniAuthController {
  private final WechatMiniLoginService login;

  public WechatMiniAuthController(WechatMiniLoginService login) {
    this.login = login;
  }

  /**
   * 用小程序 {@code wx.login} 得到的 code 换取买家会话。
   *
   * <p>POST {@code /auth/mini-wechat/login}，公开。请求体只有 {@code code}（1~512 字符），
   * 服务端直接调微信 {@code jscode2session}：code 无效/已用 401 {@code WECHAT_AUTH_INVALID}，
   * 微信拒绝登录 401 {@code WECHAT_AUTH_REJECTED}，调用过频 429，未配置 503。
   *
   * <p>不要求手机号，也不返回微信会话密钥；openid 相同则复用已有买家账号。
   * 非幂等：每次成功签发新会话，重复用同一个 code 必失败（微信侧只能消费一次）。
   *
   * @param request 仅用于取来源 IP 做限流
   * @return 200 与 {@code accessToken}、买家资料及 {@code isNewUser} 标记
   */
  @PostMapping("/login")
  public ResponseEntity<Object> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(200, login.login(body, request.getRemoteAddr()));
  }
}
