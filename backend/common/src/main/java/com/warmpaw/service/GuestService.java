package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 签发统计和 AI 会话使用的游客身份；AI 会话本身由 Python 服务创建。
 *
 * <p>游客是"未登录但需要被区分"的第三类主体：{@code AccessLevel.VISITOR} 允许
 * {@code buyer} 或 {@code guest}，用于浏览行为统计、AI 问诊这类不需要账号但需要归属的功能。
 *
 * <p><b>与 {@code AuthService} 的会话完全不同</b>：
 *
 * <ul>
 *   <li>不落库——只存 {@link TemporaryStore}（key 是 token 的 SHA-256 摘要），TTL 24 小时；
 *   <li>不可撤销——没有 session 表，退出即丢弃 token（服务端那条记录自然过期）；
 *   <li>不可跨实例（未启用 Redis 时）——签在 A 实例的 token 到 B 实例查不到，
 *       表现为立刻"已过期"。这是把游客身份放在临时存储里的直接代价。
 * </ul>
 *
 * <p>存摘要而不存原文，理由与账号会话一致：存储泄露也拿不到可用 token。
 */
@Service
public class GuestService {
  private final TemporaryStore temp;
  private final AuthService auth;

  public GuestService(TemporaryStore temp, AuthService auth) {
    this.temp = temp;
    this.auth = auth;
  }

  /**
   * 签发游客 token（明文只在这里出现一次）。
   *
   * <p>限流按 IP 每小时 20 个：游客身份无需任何凭据即可申请，不限制的话它就是
   * 一个免费的"无限身份工厂"，能把统计口径和 AI 调用额度全部稀释掉。
   *
   * <p>有效期 24 小时，且<b>不滑动</b>——{@code put} 只在创建时设置 TTL，后续访问不续期。
   * 这样单个游客的统计窗口有明确终点，不会因为一直在线而永不过期。
   *
   * <p>{@code expiresAt} 直接按"现在 + 86400"算出给前端，与存储 TTL 用的是同一个秒数常量。
   */
  public Map<String, Object> create(String ip) {
    temp.limit("guest:" + ip, 20, 3600);
    String token = auth.randomToken(), id = com.warmpaw.common.Json.id("guest");
    temp.put("guest:" + hash(token), id, 86400);
    return map("guestToken", token, "expiresAt", Instant.now().plusSeconds(86400).toString());
  }

  /**
   * 用游客 token 换取 {@code Actor}。
   *
   * <p>两种情况分开报错：<b>没带 token</b> → 401 {@code UNAUTHORIZED}（前端该去申请一个）；
   * <b>带了但查不到</b> → 401 {@code TOKEN_EXPIRED}（前端该静默重新申请）。
   * 这与 {@code AuthService.identify} 对"缺失"与"无效"的处理思路一致。
   *
   * <p>返回的 {@code phone} 恒为 null——游客没有手机号，用 {@code role="guest"} 区分身份类型。
   * {@code tokenHash} 填的是摘要，便于日志/审计在不接触明文的前提下定位同一个游客。
   */
  public AuthService.Actor identify(String token) {
    require(token != null, 401, "UNAUTHORIZED", "缺少游客身份");
    String id = temp.get("guest:" + hash(token));
    require(id != null, 401, "TOKEN_EXPIRED", "游客身份已过期");
    return new AuthService.Actor(id, "guest", null, hash(token));
  }
}
