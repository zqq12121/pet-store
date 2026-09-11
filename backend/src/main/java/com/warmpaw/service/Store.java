package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.mapper.ResourceMapper;
import com.warmpaw.persistence.RelationalRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/** 保持现有业务契约，实际读写独立领域表；快照与可变商品数据隔离。 */
@Component
public class Store {
  public final ResourceMapper mapper;

  private final RelationalRepository repository;

  public Store(ResourceMapper mapper, RelationalRepository repository) {
    this.mapper = mapper;
    this.repository = repository;
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
}
