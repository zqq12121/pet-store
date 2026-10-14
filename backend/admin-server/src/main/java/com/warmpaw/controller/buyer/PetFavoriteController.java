package com.warmpaw.controller.buyer;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OperationResult;
import com.warmpaw.controller.support.ApiRequestExecutor;
import com.warmpaw.service.PetFavoriteService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 账号收藏接口：归属来自买家会话，不接收客户端用户 ID。 */
@RestController
@RequestMapping("/api/v1/me/favorites")
public class PetFavoriteController {
  private final ApiRequestExecutor requests;
  private final PetFavoriteService service;

  public PetFavoriteController(ApiRequestExecutor requests, PetFavoriteService service) {
    this.requests = requests;
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<Object> list(@RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(request, Map.of(), AccessLevel.BUYER, false,
        context -> new OperationResult(200, service.list(query, context.actor())));
  }

  @GetMapping("/{petId}")
  public ResponseEntity<Object> state(@PathVariable String petId, HttpServletRequest request) {
    return requests.execute(request, Map.of(), AccessLevel.BUYER, false,
        context -> new OperationResult(200, service.state(petId, context.actor())));
  }

  @PutMapping("/{petId}")
  public ResponseEntity<Object> update(@PathVariable String petId, @RequestBody Map<String, Object> body,
      HttpServletRequest request) {
    return requests.execute(request, body, AccessLevel.BUYER, false,
        context -> new OperationResult(200, service.update(petId, body, context.actor())));
  }
}
