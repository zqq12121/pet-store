package com.warmpaw.service;

import static com.warmpaw.common.Json.*;
import static com.warmpaw.service.CatalogReader.queryLong;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 动物收藏只使用服务端会话身份；复用公共事务锁和关系表持久化。
 *
 * <p><b>为什么是 {@code Propagation.MANDATORY}</b>：收藏本身没有独立的事务语义，它必须挂在
 * 调用方（控制器执行器）已经开启的写事务里。MANDATORY 会在"没事务就调用"时直接抛异常，
 * 把"忘了开事务"变成联调期就能发现的错误，而不是线上悄悄写不进去。
 *
 * <p><b>业务键 {@code 用户:宠物}</b>：同一人对同一只宠物只有一行，收藏与取消都是改这一行的状态，
 * 于是天然幂等，也不会因为反复点击产生重复行。
 *
 * <p>本类不判断宠物是否可售（那是 {@code CatalogService.purchasable} 的职责），
 * 只在"新增收藏"时要求宠物公开可读；取消收藏不看状态——下架的宠物必须还能被取消。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PetFavoriteService {
  private final BusinessRepository store;
  private final CatalogService catalog;

  public PetFavoriteService(BusinessRepository store, CatalogService catalog) {
    this.store = store;
    this.catalog = catalog;
  }

  /**
   * 我的收藏列表（默认 12 条、最多 50 条）。
   *
   * <p>下架的宠物不再有公开档案，但仍返回一个占位项（{@code available=false} + "已下架动物"）
   * 并保留 {@code id}：否则用户会看到列表少了一条却无从取消，那条收藏会永远留在库里。
   *
   * <p>只统计 {@code active} 行，{@code inactive} 视为"已取消"，不占分页位置。
   * 分页在内存里做，先过滤后 skip/limit，所以 {@code total} 与当前页是同一份数据算出来的。
   */
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

  /** 单只宠物的收藏状态；按业务键直查，不做可见性判断（宠物被下架后详情页仍要能显示按钮态）。 */
  public Map<String, Object> state(String petId, AuthService.Actor actor) {
    var favorite = store.byKey("pet_favorite", actor.id() + ":" + petId);
    return map("favorited", favorite != null && "active".equals(text(favorite, "status")));
  }

  /**
   * PUT 提交目标状态而非切换，超时重试或重复点击不会反转收藏。
   *
   * <p>{@code active=true} 时要求宠物当前公开可读（下架或不存在一律 404，见
   * {@code CatalogReader.publicPet}），防止收藏一个已经买不到的商品；{@code active=false} 刻意不做
   * 这个校验——取消收藏必须永远可用，否则宠物一下架，用户的收藏就再也清不掉了。
   *
   * <p>两个分支都只在"状态确实要变"时写库：已是目标状态就直接返回，不制造多余的
   * {@code updatedAt} 变更与版本自增（减少无意义的写放大与乐观锁冲突）。
   *
   * <p>返回的 {@code favorited} 是请求的目标值而不是重新查询的结果，与写入分支一一对应。
   */
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
