package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台门店配置接口：仅适配 HTTP，具体业务在 CatalogApplicationService（调用方：店长）。
 *
 * <p>路由前缀 {@code /api/v1}，路径 {@code /admin/shop}。
 * <b>权限模型：{@link AccessLevel#ADMIN}</b>（{@code AuthService.role(actor, "admin")}）——
 * 门店配置影响营业时间、交易时限与买家看到的联系方式，因此<b>员工也不能读写</b>，
 * 与只读的宠物列表（STAFF）刻意不同。
 *
 * <p>单店系统，没有 shopId 参数：目标门店由服务层唯一确定。写操作在全局写锁内执行。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminShopController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public AdminShopController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /admin/shop；访问级别：ADMIN。
   *
   * <p>读取门店完整配置：名称、地址、经纬度与坐标系（gcj02）、联系方式、营业时间、
   * 自提说明、轮播图、{@code version}，以及两个交易时限
   * （{@code paymentTimeoutMinutes}、{@code pickupRetentionHours}）。
   *
   * <p>返回字段是显式白名单，不含内部审计字段；{@code version} 必须回传给更新接口做乐观锁。
   * 只读、幂等。
   */
  @GetMapping("/admin/shop")
  public ResponseEntity<Object> detail(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.adminShop());
  }

  /**
   * PUT /admin/shop；访问级别：ADMIN。
   *
   * <p><b>全量覆盖式更新</b>，成功 200：未提供的可选字段会被清空（联系方式存空串），
   * 因此前端必须提交完整表单而不是只提交改动项。必须带 {@code version}，冲突 409。
   *
   * <p>关键约束：{@code businessHours} 必须能通过 {@code AppointmentHours.parse}
   * （否则 400——存进无法解析的值会让预约功能整体失效）；经纬度必须同时填或同时留空；
   * {@code coordinateSystem} 固定 {@code gcj02}；{@code paymentTimeoutMinutes} 5~120、
   * {@code pickupRetentionHours} 1~168（交易时限属敏感配置）。
   *
   * <p>轮播图整体替换（≤10 张）：每张校验图片归属与用途 banner、跳转类型
   * （pet/notice/none）与附加字段匹配，已有 id 必须属于当前门店，防止改到别家店。
   *
   * <p>非幂等（每次写入刷新 {@code updatedAt} 与版本号），重复提交需用新 version。
   *
   * @param requestBody 可为 null（按空对象处理，随后因缺 version/必填项 400）
   * @return 200 与更新后的门店配置
   */
  @PutMapping("/admin/shop")
  public ResponseEntity<Object> update(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.updateShop(body, context.actor()));
  }
}
