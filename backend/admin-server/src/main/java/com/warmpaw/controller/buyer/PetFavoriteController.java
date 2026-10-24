package com.warmpaw.controller.buyer;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OperationResult;
import com.warmpaw.controller.support.ApiRequestExecutor;
import com.warmpaw.service.PetFavoriteService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 账号收藏接口：归属来自买家会话，不接收客户端用户 ID（调用方：买家）。
 *
 * <p>路由前缀 {@code /api/v1/me/favorites}。
 *
 * <p><b>权限模型：三个方法都是 {@link AccessLevel#BUYER}</b>，即
 * {@code AuthService.role(actor, "buyer")}——未登录 401，店长/员工令牌 403。
 * 收藏是纯买家功能，后台不使用。
 *
 * <p>委托 {@link PetFavoriteService}，关系表按 {@code ownerId + petId} 做业务键，
 * 因此同一只宠物的收藏状态天然唯一，取消再收藏不会产生多条记录。
 */
@RestController
@RequestMapping("/api/v1/me/favorites")
public class PetFavoriteController {
  private final ApiRequestExecutor requests;
  private final PetFavoriteService service;

  public PetFavoriteController(ApiRequestExecutor requests, PetFavoriteService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * 我的收藏列表。
   *
   * <p>GET {@code /me/favorites?page=1&pageSize=12}，权限 BUYER。
   * 只查 {@code status=active} 的收藏，按录入顺序分页（{@code pageSize} 默认 12、上限 50）。
   *
   * <p>已下架的宠物不返回完整卡片，而是占位 {@code name="已下架动物"} 且
   * {@code available=false}——保留条目与取消收藏入口，避免列表无提示地变短。
   *
   * <p>只读、幂等；{@code total} 是收藏总数（不是当前页条数）。
   */
  @GetMapping
  public ResponseEntity<Object> list(@RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(request, Map.of(), AccessLevel.BUYER, false,
        context -> new OperationResult(200, service.list(query, context.actor())));
  }

  /**
   * 单只宠物的收藏状态。
   *
   * <p>GET {@code /me/favorites/{petId}}，权限 BUYER。只返回
   * {@code favorited=true|false}，<b>不校验宠物是否存在</b>——详情页可以先用它点亮图标，
   * 不必因为宠物下架而报错。
   *
   * <p>只读、幂等，适合在列表页批量调用（每次一个宠物）。
   */
  @GetMapping("/{petId}")
  public ResponseEntity<Object> state(@PathVariable String petId, HttpServletRequest request) {
    return requests.execute(request, Map.of(), AccessLevel.BUYER, false,
        context -> new OperationResult(200, service.state(petId, context.actor())));
  }

  /**
   * 设置收藏状态（提交目标值，不做取反）。
   *
   * <p>PUT {@code /me/favorites/{petId}}，权限 BUYER。body 只有 {@code active} 布尔：
   * true 收藏、false 取消。<b>PUT 提交目标状态而非切换，超时重试或重复点击不会反转收藏</b>，
   * 所以本接口可以安全重试。
   *
   * <p>收藏时才会校验宠物可公开访问（下架/不存在 404），取消时不校验——否则已下架宠物
   * 将永远无法从收藏夹移除。
   *
   * @return 200 与 {@code favorited}（回显目标状态，不是数据库实际值的二次查询）
   */
  @PutMapping("/{petId}")
  public ResponseEntity<Object> update(@PathVariable String petId, @RequestBody Map<String, Object> body,
      HttpServletRequest request) {
    return requests.execute(request, body, AccessLevel.BUYER, false,
        context -> new OperationResult(200, service.update(petId, body, context.actor())));
  }
}
