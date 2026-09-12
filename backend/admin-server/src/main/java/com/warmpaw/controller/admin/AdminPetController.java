package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台宠物管理接口：仅适配 HTTP，具体业务在 CatalogApplicationService。 */
@RestController
@RequestMapping("/api/v1")
public class AdminPetController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public AdminPetController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /** GET /admin/pets；访问级别：ADMIN。 */
  @GetMapping("/admin/pets")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.adminPets(query));
  }

  /** GET /admin/pets/{petId}；访问级别：ADMIN。 */
  @GetMapping("/admin/pets/{petId}")
  public ResponseEntity<Object> detail(@PathVariable String petId, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.adminPet(petId));
  }

  /** POST /admin/pets；访问级别：ADMIN。 */
  @PostMapping("/admin/pets")
  public ResponseEntity<Object> create(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.createPet(body, context.actor()));
  }

  /** PUT /admin/pets/{petId}；访问级别：ADMIN。 */
  @PutMapping("/admin/pets/{petId}")
  public ResponseEntity<Object> update(
      @PathVariable String petId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.updatePet(petId, body, context.actor()));
  }

  /** POST /admin/pets/{petId}/publish；访问级别：ADMIN。 */
  @PostMapping("/admin/pets/{petId}/publish")
  public ResponseEntity<Object> publish(
      @PathVariable String petId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.publishPet(petId, body, context.actor()));
  }

  /** POST /admin/pets/{petId}/unpublish；访问级别：ADMIN。 */
  @PostMapping("/admin/pets/{petId}/unpublish")
  public ResponseEntity<Object> unpublish(
      @PathVariable String petId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.unpublishPet(petId, body, context.actor()));
  }
}
