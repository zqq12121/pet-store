package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 公开门店信息接口：仅适配 HTTP，具体业务在 CatalogApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class ShopController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public ShopController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /shop；访问级别：PUBLIC。 */
  @GetMapping("/shop")
  public ResponseEntity<Object> detail(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.shop());
  }
}
