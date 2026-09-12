package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 业务公共边界：统一事务、权限和幂等，不包含接口路径路由。 */
@Service
public class BusinessOperationExecutor {
  private final BusinessRepository store;
  private final BusinessHooks hooks;
  public BusinessOperationExecutor(BusinessRepository store, BusinessHooks hooks) {
    this.store = store;
    this.hooks = hooks;
  }

  /** 一次业务调用一个事务：先鉴权，再加锁、重放、执行业务、保存幂等结果。 */
  @org.springframework.transaction.annotation.Transactional
  public OperationResult execute(
      RequestContext context,
      Map<String, Object> body,
      AccessLevel access,
      boolean idempotent,
      java.util.function.Supplier<OperationResult> operation) {
    String method = context.method(), path = context.path(), key = context.idempotencyKey();
    AuthService.Actor actor = context.actor();
    boolean admin = access == AccessLevel.ADMIN, write = !context.method().equals("GET");
    access.check(context.actor());
    if (write) store.lock();
    boolean idem = write && idempotent;
    String scope = null;
    if (idem) {
      require(
          key != null
              && key.matches(
                  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
          400,
          "VALIDATION_ERROR",
          "请提供 UUID 格式的 Idempotency-Key");
      scope = hash(actor.id() + "\n" + method + "\n" + path + "\n" + key);
      Map<String, Object> old = store.replay(scope);
      if (old != null) {
        require(
            hash(canonical(body)).equals(old.get("body_hash")),
            409,
            "IDEMPOTENCY_CONFLICT",
            "同一幂等键的请求内容不同");
        return hooks.replay(
            read(old.get("response_body").toString()),
            path,
            admin,
            ((Number) old.get("http_status")).intValue());
      }
    }
    OperationResult reply = operation.get();
    if (idem)
      store.remember(
          scope,
          hash(canonical(body)),
          reply.resource(),
          com.warmpaw.common.Json.write(
              map("data", reply.data(), "kind", reply.kind(), "resource", reply.resource())),
          reply.status(),
          Instant.now().toString());
    return reply;
  }

}
