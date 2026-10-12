package com.warmpaw.controller;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OperationResult;
import com.warmpaw.controller.support.ApiRequestExecutor;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CommunityService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 社区 HTTP 入口：每条路由显式声明身份，写入复用公共事务锁。 */
@RestController
@RequestMapping("/api/v1")
public class CommunityController {
  private final ApiRequestExecutor requests;
  private final CommunityService service;

  public CommunityController(ApiRequestExecutor requests, CommunityService service) {
    this.requests = requests;
    this.service = service;
  }

  private ResponseEntity<Object> run(HttpServletRequest request, Map<String, Object> body,
      AccessLevel access, boolean idempotent, int status, Function<AuthService.Actor, Object> action) {
    return requests.execute(request, body, access, idempotent,
        context -> new OperationResult(status, action.apply(context.actor())));
  }

  /** 公开浏览支持分类、搜索和分页；个人筛选由业务层追加买家鉴权。 */
  @GetMapping("/community/contents")
  public ResponseEntity<Object> list(@RequestParam(defaultValue = "post") String type,
      @RequestParam(required = false) String category, @RequestParam(required = false) String query,
      @RequestParam(defaultValue = "all") String scope, @RequestParam(defaultValue = "1") int page,
      HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.PUBLIC, false, 200,
        actor -> service.list(type, category, query, scope, page, actor));
  }

  @GetMapping("/community/contents/{id}")
  public ResponseEntity<Object> detail(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.PUBLIC, false, 200, actor -> service.detail(id, actor));
  }

  /** 创建帖子、评价及评论使用幂等键，避免超时重试重复产生内容和通知。 */
  @PostMapping("/community/contents")
  public ResponseEntity<Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, true, 201, actor -> service.create(body, actor));
  }

  @PutMapping("/community/contents/{id}")
  public ResponseEntity<Object> update(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, false, 200, actor -> service.update(id, body, actor));
  }

  @DeleteMapping("/community/contents/{id}")
  public ResponseEntity<Object> delete(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, actor -> service.deleteContent(id, actor));
  }

  @GetMapping("/community/eligible-orders")
  public ResponseEntity<Object> eligibleOrders(HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, service::eligibleOrders);
  }

  @GetMapping("/community/contents/{id}/comments")
  public ResponseEntity<Object> comments(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.PUBLIC, false, 200, actor -> service.comments(id, actor, false));
  }

  @PostMapping("/community/contents/{id}/comments")
  public ResponseEntity<Object> comment(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, true, 201, actor -> service.comment(id, body, actor));
  }

  @DeleteMapping("/community/comments/{id}")
  public ResponseEntity<Object> deleteComment(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, actor -> service.deleteComment(id, actor));
  }

  @PutMapping("/community/contents/{id}/reactions/{type}")
  public ResponseEntity<Object> reaction(@PathVariable String id, @PathVariable String type,
      @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, false, 200, actor -> service.reaction(id, type, body, actor));
  }

  @PostMapping("/community/reports")
  public ResponseEntity<Object> report(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, true, 201, actor -> service.report(body, actor));
  }

  @GetMapping("/community/notices")
  public ResponseEntity<Object> notices(HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, service::notices);
  }

  @PutMapping("/community/notices/{id}/read")
  public ResponseEntity<Object> readNotice(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, actor -> service.readNotice(id, actor));
  }

  /** 管理后台内容、举报和词库接口不允许买家令牌访问。 */
  @GetMapping("/admin/community/contents")
  public ResponseEntity<Object> adminContents(@RequestParam(required = false) String type, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.adminContents(type, actor));
  }

  @GetMapping("/admin/community/contents/{id}")
  public ResponseEntity<Object> adminDetail(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.adminDetail(id, actor));
  }

  @GetMapping("/admin/community/contents/{id}/comments")
  public ResponseEntity<Object> adminComments(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.comments(id, actor, true));
  }

  @PostMapping("/admin/community/contents/{id}/comments")
  public ResponseEntity<Object> shopReply(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.ADMIN, true, 201, actor -> service.comment(id, body, actor));
  }

  @DeleteMapping("/admin/community/comments/{id}")
  public ResponseEntity<Object> deleteShopReply(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.deleteComment(id, actor));
  }

  @PostMapping("/admin/community/{type}/{id}/hide")
  public ResponseEntity<Object> hide(@PathVariable String type, @PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.hide(type, id, actor));
  }

  @GetMapping("/admin/community/reports")
  public ResponseEntity<Object> reports(HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.reports());
  }

  @PutMapping("/admin/community/reports/{id}")
  public ResponseEntity<Object> handleReport(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.ADMIN, false, 200, actor -> service.handleReport(id, body, actor));
  }

  @GetMapping("/admin/community/words")
  public ResponseEntity<Object> words(HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.words());
  }

  @PostMapping("/admin/community/words")
  public ResponseEntity<Object> createWord(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.ADMIN, false, 201, actor -> service.saveWord(null, body, actor));
  }

  @PutMapping("/admin/community/words/{id}")
  public ResponseEntity<Object> updateWord(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.ADMIN, false, 200, actor -> service.saveWord(id, body, actor));
  }
}
