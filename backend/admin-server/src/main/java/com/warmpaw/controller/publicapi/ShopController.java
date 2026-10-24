package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 公开门店信息接口：仅适配 HTTP，具体业务在 CatalogApplicationService（调用方：公开）。
 *
 * <p>路由前缀 {@code /api/v1}，路径 {@code /shop}。
 *
 * <p><b>权限模型：{@link AccessLevel#PUBLIC}</b>，无需登录。输出是"顾客该看到的部分"：
 * 地址、营业时间、联系方式、自提说明、地图坐标，<b>不含</b>交易时限、轮播配置、
 * 版本号等后台字段——那些只在 {@code /admin/shop} 暴露。
 *
 * <p>委托 {@link CatalogApplicationService}，与后台读取共用同一份门店数据、不同投影。
 */
@RestController
@RequestMapping("/api/v1")
public class ShopController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public ShopController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /shop；访问级别：PUBLIC。
   *
   * <p>返回门店公开资料：名称、地址、经纬度（{@code gcj02}）、电话/微信、营业时间、
   * 自提说明。联系方式可能为空串（未配置），前端需容忍空值而不是显示占位号码。
   *
   * <p>营业时间直接决定预约能否创建，这里读到的字符串与预约校验使用同一份配置。
   * 无参数、只读、幂等。
   */
  @GetMapping("/shop")
  public ResponseEntity<Object> detail(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.shop());
  }
}
