package com.warmpaw.service;

import static com.warmpaw.common.Json.*;
import static com.warmpaw.service.CatalogReader.queryLong;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 动物收藏只使用服务端会话身份；复用公共事务锁和关系表持久化。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PetFavoriteService {
  private final BusinessRepository store;
  private final CatalogService catalog;

  public PetFavoriteService(BusinessRepository store, CatalogService catalog) {
    this.store = store;
    this.catalog = catalog;
  }

  public Map<String, Object> list(Map<String, Object> query, AuthService.Actor actor) {
    new Input(query, "page,pageSize");
    long page = queryLong(query, "page", 1, 1, 100000);
    long size = queryLong(query, "pageSize", 12, 1, 50);
    var favorites = store.list("pet_favorite").stream()
        .filter(f -> actor.id().equals(text(f, "ownerId")) && "active".equals(text(f, "status")))
        .toList();
    // 下架动物不再公开档案，但保留占位和取消收藏入口。
    var items = favorites.stream().skip((page - 1) * size).limit(size).map(f -> {
      String id = text(f, "petId");
      var pet = store.find("pet", id);
      boolean available = pet != null && !"off".equals(text(pet, "status"));
      var result = available ? catalog.card(pet) : map("id", id, "name", "已下架动物");
      result.put("available", available);
      return result;
    }).toList();
    return map("items", items, "total", favorites.size());
  }

  public Map<String, Object> state(String petId, AuthService.Actor actor) {
    var favorite = store.byKey("pet_favorite", actor.id() + ":" + petId);
    return map("favorited", favorite != null && "active".equals(text(favorite, "status")));
  }

  /** PUT 提交目标状态而非切换，超时重试或重复点击不会反转收藏。 */
  public Map<String, Object> update(String petId, Map<String, Object> body, AuthService.Actor actor) {
    boolean active = new Input(body, "active").bool("active");
    if (active) catalog.publicPet(petId);
    String key = actor.id() + ":" + petId;
    var favorite = store.byKey("pet_favorite", key);
    if (favorite == null && active)
      store.create("pet_favorite", actor.id(), key, map("petId", petId, "status", "active"));
    else if (favorite != null && active != "active".equals(text(favorite, "status"))) {
      favorite.put("status", active ? "active" : "inactive");
      store.save(favorite);
    }
    return map("favorited", active);
  }
}
