package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台门店配置接口：仅适配 HTTP，具体业务在 CatalogApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AdminShopController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public AdminShopController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /admin/shop；访问级别：ADMIN。 */
  @GetMapping("/admin/shop")
  public ResponseEntity<Object> detail(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.adminShop());
  }

  /** PUT /admin/shop；访问级别：ADMIN。 */
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
