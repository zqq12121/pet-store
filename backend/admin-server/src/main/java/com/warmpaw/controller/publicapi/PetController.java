package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 公开宠物查询接口：仅适配 HTTP，具体业务在 CatalogApplicationService（调用方：公开）。
 *
 * <p>路由前缀 {@code /api/v1}，路径 {@code /pet-categories}、{@code /pets}、{@code /pets/{petId}}。
 *
 * <p><b>权限模型：全部 {@link AccessLevel#PUBLIC}</b>，不识别身份，也不因登录而多返回字段——
 * 买家侧的个性化信息（收藏状态）由 {@code /me/favorites} 提供，不在这里混入。
 *
 * <p>可见性由服务层控制：<b>列表</b>只返回 {@code on_sale}/{@code reserved}；<b>详情</b>除
 * {@code off} 外都可读（{@code sold} 仍允许回看），但只有可售状态会得到
 * {@code purchaseAllowed=true}。{@code off} 与不存在的 id 一律 404，避免探测未上架库存。
 */
@RestController
@RequestMapping("/api/v1")
public class PetController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public PetController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /pet-categories；访问级别：PUBLIC。
   *
   * <p>返回分类导航：猫/狗的 {@code code}/{@code name} 及各自可选品种列表（取自非 off 的宠物，
   * 去重后给出），供筛选栏渲染，避免出现"点进去空结果"的品种。
   *
   * <p>无参数、只读、幂等。
   */
  @GetMapping("/pet-categories")
  public ResponseEntity<Object> categories(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.categories());
  }

  /**
   * GET /pets；访问级别：PUBLIC。
   *
   * <p>宠物列表与筛选。查询参数白名单：{@code keyword}、{@code category}(cat|dog)、
   * {@code breed}、{@code minPriceAmount}/{@code maxPriceAmount}（分，须 min≤max）、
   * {@code gender}(male|female)、{@code minAgeMonths}/{@code maxAgeMonths}（0~360）、
   * {@code color}、{@code status}(on_sale|reserved)、{@code sort}、{@code page}/{@code pageSize}。
   *
   * <p>{@code sort} 只接受 comprehensive（综合）/price_asc/price_desc/newest；
   * 非法枚举或 min>max 一律 400。参数白名单之外的多余 query 同样 400，
   * 因此前端不要顺手带上缓存变量。
   *
   * <p>只读、幂等；返回卡片投影（不含后台的版本号、审核备注）。
   */
  @GetMapping("/pets")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.pets(query));
  }

  /**
   * GET /pets/{petId}；访问级别：PUBLIC。
   *
   * <p>宠物公开详情：图片/视频、健康与检疫信息（脱敏后的公开投影）、性格标签、门店自提说明，
   * 以及关键的 {@code purchaseAllowed}/{@code purchaseBlockedReason}——能否下单由服务层按状态、
   * 检疫与库存占用综合判定，前端不要自己用 status 猜。
   *
   * <p>只有 {@code off} 和不存在的 id 返回 404；{@code sold} 仍可查看（买家回看已购宠物），
   * 此时 {@code purchaseAllowed=false}。只读、幂等。
   */
  @GetMapping("/pets/{petId}")
  public ResponseEntity<Object> detail(@PathVariable String petId, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.pet(petId));
  }
}
