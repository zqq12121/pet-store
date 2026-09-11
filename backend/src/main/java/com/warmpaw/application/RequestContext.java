package com.warmpaw.application;

import com.warmpaw.service.AuthService;

/** 单次调用上下文：保留原始路径作为幂等作用域，不用路径分发业务。 */
public record RequestContext(
    String method, String path, String idempotencyKey, AuthService.Actor actor, String ip) {}
