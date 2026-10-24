package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 首页聚合接口：仅适配 HTTP，具体业务在 CatalogApplicationService（调用方：公开）。
 *
 * <p>路由前缀 {@code /api/v1}，路径 {@code /home}。
 *
 * <p><b>权限模型：{@link AccessLevel#PUBLIC}</b>，任何人不带令牌都能访问；响应只含公开数据
 * （上架/已预定宠物、启用的轮播、分类、门店公开信息），不含库存锁定细节与买家信息。
 *
 * <p>委托 {@link CatalogApplicationService}。聚合在服务端一次完成，是为了避免首屏串行发起
 * 五六个请求。
 */
@RestController
@RequestMapping("/api/v1")
public class HomeController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public HomeController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /home；访问级别：PUBLIC。
   *
   * <p>返回首页所需的全部板块：{@code banners}（只含 enabled 且跳转目标仍有效的）、
   * {@code categories}、{@code recommendedPets}（推荐位，最多 8）、{@code latestPets}
   * （按发布时间倒序，最多 8）与 {@code shop} 公开信息。
   *
   * <p>候选宠物只取 {@code on_sale}/{@code reserved}，所以 {@code off}/{@code sold} 不会出现在
   * 首页；轮播若指向已下架宠物会被过滤掉，避免用户点进死链。
   *
   * <p>查询参数白名单为空：传任何 query 参数都会 400。只读、幂等。
   */
  @GetMapping("/home")
  public ResponseEntity<Object> home(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.home(query));
  }
}
