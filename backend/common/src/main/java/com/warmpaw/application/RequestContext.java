package com.warmpaw.application;

import com.warmpaw.service.AuthService;

/**
 * 单次调用上下文：保留原始路径作为幂等作用域，不用路径分发业务。
 *
 * <p>它是 HTTP 层与业务执行层之间<b>唯一的耦合面</b>：业务方法拿不到
 * {@code HttpServletRequest}，只能从这里取所需信息，因此 service 层天然可测试、
 * 也不会顺手去读请求头。
 *
 * @param method         HTTP 方法；{@code GET} 是"不加锁、不做幂等"的判据
 * @param path           去掉 {@code /api/v1} 前缀后的路径，参与幂等作用域计算，
 *                       所以<b>路径变更会使旧的幂等键失效</b>
 * @param idempotencyKey 请求头 {@code Idempotency-Key}，可为 null；需要幂等的写接口必须给 UUID
 * @param actor          已认证主体，匿名访问时为 null
 * @param ip             客户端地址，用于限流与支付侧风控；依赖反向代理正确透传
 */
public record RequestContext(
    String method, String path, String idempotencyKey, AuthService.Actor actor, String ip) {}
