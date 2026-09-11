package com.warmpaw.controller.publicapi;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 公开宠物查询接口：仅适配 HTTP，具体业务在 CatalogApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class PetController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public PetController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /pet-categories；访问级别：PUBLIC。 */
  @GetMapping("/pet-categories")
  public ResponseEntity<Object> categories(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.categories());
  }

  /** GET /pets；访问级别：PUBLIC。 */
  @GetMapping("/pets")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.pets(query));
  }

  /** GET /pets/{petId}；访问级别：PUBLIC。 */
  @GetMapping("/pets/{petId}")
  public ResponseEntity<Object> detail(@PathVariable String petId, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.PUBLIC, false, context -> service.pet(petId));
  }
}
