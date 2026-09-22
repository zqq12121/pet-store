package com.warmpaw.repository;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.mapper.ResourceMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 保持现有业务契约，实际读写独立领域表；快照与可变商品数据隔离。 */
@Repository
public class BusinessRepository {
  private final ResourceMapper mapper;

  private final RelationalRepository repository;

  public BusinessRepository(ResourceMapper mapper, RelationalRepository repository) {
    this.mapper = mapper;
    this.repository = repository;
  }

  /** 单店写事务统一加锁，避免订单和退款各自采用不同锁序。 */
  public void lock() {
    mapper.lock();
  }

  public void occupy(String petId, String orderId) {
    mapper.occupy(petId, orderId);
  }

  public String occupation(String petId) {
    return mapper.occupation(petId);
  }

  public int release(String petId, String orderId) {
    return mapper.release(petId, orderId);
  }

  public Map<String, Object> replay(String scope) {
    return mapper.replay(scope);
  }

  public void remember(
      String scope, String hash, String resource, String body, int status, String now) {
    mapper.remember(scope, hash, resource, body, status, now);
  }

  public Map<String, Object> find(String kind, String id) {
    return repository.find(kind, id);
  }

  public Map<String, Object> get(String kind, String id) {
    Map<String, Object> m = find(kind, id);
    require(m != null, 404, "RESOURCE_NOT_FOUND", "资源不存在或不可访问");
    return m;
  }

  public Map<String, Object> byKey(String kind, String key) {
    return repository.byKey(kind, key);
  }

  public Map<String, Object> userByUsername(String username) {
    return repository.userByUsername(username);
  }

  public void revokeSessions(String owner, String role) {
    repository.revokeSessions(owner, role);
  }

  public List<Map<String, Object>> list(String kind) {
    return repository.list(kind);
  }

  public Map<String, Object> create(String kind, String owner, String key, Map<String, Object> m) {
    m.putIfAbsent("id", id(kind));
    m.putIfAbsent("status", "ready");
    m.putIfAbsent("version", 1);
    m.putIfAbsent("createdAt", Instant.now().toString());
    m.putIfAbsent("updatedAt", m.get("createdAt"));
    if (owner != null) m.put("ownerId", owner);
    repository.insert(kind, key, m);
    return m;
  }

  public void save(Map<String, Object> m) {
    m.put("updatedAt", Instant.now().toString());
    if (m.get("version") instanceof Number) m.put("version", number(m, "version") + 1);
    repository.update(m);
  }

  public void audit(String actor, String action, String resource) {
    mapper.audit(id("audit"), actor, action, resource, Instant.now().toString());
  }

  public static void version(Map<String, Object> entity, long expected) {
    require(number(entity, "version") == expected, 409, "VERSION_CONFLICT", "数据已更新，请重新加载");
  }

  public static void state(Map<String, Object> entity, String... states) {
    require(
        Arrays.asList(states).contains(text(entity, "status")),
        409,
        "ORDER_STATE_CONFLICT",
        "当前状态不允许此操作");
  }
  /** 文件和订单共用的只读归属校验，避免文件服务依赖交易服务。 */
  public Map<String, Object> ownedOrder(String id, com.warmpaw.service.AuthService.Actor actor) {
    Map<String, Object> o = get("order", id);
    require(
        actor != null && (actor.role().equals("admin") || actor.id().equals(text(o, "ownerId"))),
        404,
        "RESOURCE_NOT_FOUND",
        "订单不存在或不可访问");
    return o;
  }

}
