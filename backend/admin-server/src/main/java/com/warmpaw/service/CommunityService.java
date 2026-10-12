package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Service;

/** 单店社区业务：复用事务执行器和关系表，公开 DTO 不包含手机号或订单详情。 */
@Service
public class CommunityService {
  private final BusinessRepository store;

  public CommunityService(BusinessRepository store) {
    this.store = store;
  }

  private boolean admin(AuthService.Actor actor) {
    return actor != null && "admin".equals(actor.role());
  }

  private boolean mine(Map<String, Object> entity, AuthService.Actor actor) {
    return actor != null && actor.id().equals(text(entity, "ownerId"));
  }

  private boolean published(Map<String, Object> entity) {
    return "published".equals(text(entity, "status"));
  }

  private Map<String, Object> content(String id, AuthService.Actor actor, boolean publicOnly) {
    var item = store.get("community_content", id);
    require(published(item) || !publicOnly && (mine(item, actor) || admin(actor))
        && !"deleted".equals(text(item, "status")), 404, "RESOURCE_NOT_FOUND", "内容不存在或不可访问");
    return item;
  }

  private void owned(Map<String, Object> item, AuthService.Actor actor) {
    require(mine(item, actor), 404, "RESOURCE_NOT_FOUND", "内容不存在或不可访问");
    require(!"deleted".equals(text(item, "status")), 409, "CONTENT_DELETED", "内容已删除");
  }

  /** 按 Unicode 字符计数，与前端 Array.from 一致，不把一个 emoji 计算成两位。 */
  private String field(Input input, String name, int max) {
    String label = switch (name) { case "title" -> "标题"; case "body" -> "正文";
      case "term" -> "词条"; case "reason" -> "举报原因"; default -> name; };
    Object value = input.data().get(name);
    require(value instanceof String, 400, "VALIDATION_ERROR", label + "必须为文字");
    String valueText = ((String) value).strip();
    require(!valueText.isBlank() && valueText.codePointCount(0, valueText.length()) <= max,
        400, "VALIDATION_ERROR", label + "不能为空且最多" + max + "个字符");
    return valueText;
  }

  private String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
        .replaceAll("[\\s\\p{Z}\\p{Cf}\\p{P}]", "");
  }

  private void filter(String... values) {
    String candidate = normalize(String.join("\n", values));
    boolean rejected = store.list("community_word").stream()
        .filter(w -> Boolean.TRUE.equals(w.get("enabled")))
        .anyMatch(w -> candidate.contains(text(w, "normalized")));
    require(!rejected, 422, "COMMUNITY_WORD_BLOCKED", "内容包含不当词汇，请修改后再提交");
  }

  private List<String> images(Input input, AuthService.Actor actor) {
    List<String> ids = input.has("imageFileIds") ? input.ids("imageFileIds", 0, 9) : List.of();
    require(new HashSet<>(ids).size() == ids.size(), 400, "VALIDATION_ERROR", "图片不能重复");
    for (String id : ids) {
      var file = store.get("file", id);
      require(mine(file, actor) && "community_image".equals(text(file, "purpose"))
          && "ready".equals(text(file, "status"))
          && List.of("image/jpeg", "image/png", "image/webp").contains(text(file, "mimeType"))
          && number(file, "sizeBytes") <= 5 * 1024L * 1024,
          422, "FILE_PURPOSE_MISMATCH", "只能使用本人上传的社区图片，每张不超过5 MB");
    }
    return ids;
  }

  private String name(String id, String role) {
    if ("admin".equals(role)) return "店铺";
    var user = store.find("user", id);
    return user == null ? "宠友" : Objects.toString(user.get("nickname"), "宠友");
  }

  private Map<String, Object> select(Map<String, Object> item, String fields) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key : fields.split(",")) result.put(key, item.get(key));
    return result;
  }

  private Map<String, Object> dto(Map<String, Object> item, AuthService.Actor actor) {
    var result = select(item, "id,type,title,body,category,rating,status,createdAt,updatedAt");
    result.put("authorName", name(text(item, "ownerId"), "buyer"));
    result.put("isOwner", mine(item, actor));
    result.put("images", strings(item, "imageFileIds").stream()
        .map(id -> map("id", id, "url", "/api/v1/media/" + id)).toList());
    var reactions = store.list("community_reaction").stream()
        .filter(r -> text(item, "id").equals(text(r, "contentId")) && "active".equals(text(r, "status"))).toList();
    result.put("likeCount", reactions.stream().filter(r -> "like".equals(text(r, "type"))).count());
    result.put("liked", reactions.stream().anyMatch(r -> "like".equals(text(r, "type")) && mine(r, actor)));
    result.put("favorited", reactions.stream().anyMatch(r -> "favorite".equals(text(r, "type")) && mine(r, actor)));
    if (mine(item, actor) || admin(actor)) result.put("orderId", item.get("orderId"));
    return result;
  }

  public Map<String, Object> list(String type, String category, String query, String scope, int page,
      AuthService.Actor actor) {
    require(List.of("post", "review").contains(type), 400, "VALIDATION_ERROR", "板块不正确");
    require(List.of("all", "mine", "favorites").contains(scope), 400, "VALIDATION_ERROR", "筛选不正确");
    require(page >= 1 && page <= 100000, 400, "VALIDATION_ERROR", "页码不正确");
    require(category == null || List.of("experience", "daily", "question").contains(category),
        400, "VALIDATION_ERROR", "分类不正确");
    require(query == null || query.codePointCount(0, query.length()) <= 80,
        400, "VALIDATION_ERROR", "搜索词最多80个字符");
    if (!"all".equals(scope)) AuthService.role(actor, "buyer");
    Set<String> favorites = new HashSet<>();
    if ("favorites".equals(scope)) store.list("community_reaction").stream()
        .filter(r -> mine(r, actor) && "favorite".equals(text(r, "type")) && "active".equals(text(r, "status")))
        .forEach(r -> favorites.add(text(r, "contentId")));
    String keyword = Objects.toString(query, "").strip().toLowerCase(Locale.ROOT);
    var items = store.list("community_content").stream()
        .filter(c -> type.equals(text(c, "type")))
        .filter(c -> published(c) || "mine".equals(scope) && mine(c, actor) && "hidden".equals(text(c, "status")))
        .filter(c -> !"mine".equals(scope) || mine(c, actor))
        .filter(c -> !"favorites".equals(scope) || favorites.contains(text(c, "id")))
        .filter(c -> category == null || category.equals(text(c, "category")))
        .filter(c -> keyword.isEmpty() || (Objects.toString(c.get("title"), "") + "\n" + text(c, "body"))
            .toLowerCase(Locale.ROOT).contains(keyword)).toList();
    return map("items", items.stream().skip((page - 1L) * 20).limit(20).map(c -> dto(c, actor)).toList(),
        "total", items.size(), "page", page, "pageSize", 20);
  }

  public Map<String, Object> detail(String id, AuthService.Actor actor) {
    return dto(content(id, actor, false), actor);
  }

  /** 评价唯一键不随软删除释放，事务锁与数据库约束共同防止重复和并发评价。 */
  public Map<String, Object> create(Map<String, Object> body, AuthService.Actor actor) {
    var input = new Input(body, "type,title,body,category,rating,orderId,imageFileIds");
    String type = input.choice("type", "post,review"), orderId = null;
    var item = map("type", type, "status", "published", "body", field(input, "body", "post".equals(type) ? 5000 : 1000));
    if ("post".equals(type)) {
      require(!input.has("rating") && !input.has("orderId"), 400, "VALIDATION_ERROR", "帖子不接收评价字段");
      item.put("title", field(input, "title", 80));
      item.put("category", input.choice("category", "experience,daily,question"));
    } else {
      require(!input.has("title") && !input.has("category"), 400, "VALIDATION_ERROR", "评价不接收帖子字段");
      orderId = input.str("orderId", 1, 64);
      var order = store.ownedOrder(orderId, actor);
      require("completed".equals(text(order, "status")), 409, "REVIEW_NOT_ELIGIBLE", "完成到店交付后才能评价");
      require(store.byKey("community_content", orderId) == null, 409, "ORDER_ALREADY_REVIEWED", "该订单已评价，删除后也不能再次评价");
      item.put("orderId", orderId);
      item.put("rating", input.integer("rating", 1, 5));
    }
    item.put("imageFileIds", images(input, actor));
    filter(Objects.toString(item.get("title"), ""), text(item, "body"));
    var created = store.create("community_content", actor.id(), orderId, item);
    return dto(created, actor);
  }

  public Map<String, Object> update(String id, Map<String, Object> body, AuthService.Actor actor) {
    var item = store.get("community_content", id);
    owned(item, actor);
    boolean post = "post".equals(text(item, "type"));
    var input = new Input(body, post ? "title,body,category,imageFileIds" : "body,rating,imageFileIds");
    item.put("body", field(input, "body", post ? 5000 : 1000));
    if (post) {
      item.put("title", field(input, "title", 80));
      item.put("category", input.choice("category", "experience,daily,question"));
    } else item.put("rating", input.integer("rating", 1, 5));
    item.put("imageFileIds", images(input, actor));
    filter(Objects.toString(item.get("title"), ""), text(item, "body"));
    // 修改隐藏内容不解除管理员的隐藏状态。
    store.save(item);
    return dto(item, actor);
  }

  public Object deleteContent(String id, AuthService.Actor actor) {
    var item = store.get("community_content", id);
    owned(item, actor);
    item.put("status", "deleted");
    store.save(item);
    return map("deleted", true);
  }

  public List<Map<String, Object>> eligibleOrders(AuthService.Actor actor) {
    return store.list("order").stream().filter(o -> mine(o, actor) && "completed".equals(text(o, "status")))
        .map(o -> {
          var prior = store.byKey("community_content", text(o, "id"));
          return map("id", o.get("id"), "orderNo", o.get("orderNo"),
              "petName", object(o, "product").getOrDefault("name", "到店消费"),
              "reviewed", prior != null);
        }).toList();
  }

  private boolean visibleComment(Map<String, Object> comment) {
    if (!published(comment)) return false;
    String rootId = text(comment, "rootId");
    return rootId == null || published(store.get("community_comment", rootId));
  }

  private Map<String, Object> commentDto(Map<String, Object> comment, AuthService.Actor actor) {
    var result = select(comment, "id,rootId,replyToId,body,status,createdAt");
    result.put("authorName", name(text(comment, "ownerId"), text(comment, "authorRole")));
    result.put("isShop", "admin".equals(text(comment, "authorRole")));
    result.put("isOwner", mine(comment, actor));
    if (comment.get("replyToId") != null) {
      var target = store.get("community_comment", text(comment, "replyToId"));
      result.put("replyToName", published(target)
          ? name(text(target, "ownerId"), text(target, "authorRole")) : "原回复已删除");
    }
    return result;
  }

  public List<Map<String, Object>> comments(String id, AuthService.Actor actor, boolean moderation) {
    if (moderation) store.get("community_content", id); else content(id, actor, false);
    return store.list("community_comment").stream().filter(c -> id.equals(text(c, "contentId")))
        .filter(c -> moderation ? !"deleted".equals(text(c, "status")) : visibleComment(c))
        .sorted(Comparator.comparing(c -> text(c, "createdAt")))
        .map(c -> commentDto(c, actor)).toList();
  }

  private void notify(String recipient, String role, Map<String, Object> comment,
      AuthService.Actor actor, String message) {
    if (!"buyer".equals(role) || recipient.equals(actor.id())) return;
    store.create("community_notice", recipient, text(comment, "id") + ":" + recipient,
        map("contentId", comment.get("contentId"), "commentId", comment.get("id"),
            "actorName", name(actor.id(), actor.role()), "message", message, "read", false));
  }

  public Map<String, Object> comment(String id, Map<String, Object> body, AuthService.Actor actor) {
    var item = content(id, actor, true);
    require(!admin(actor) || "review".equals(text(item, "type")), 403, "FORBIDDEN", "店铺回复用于回应店铺评价");
    var input = new Input(body, "body,replyToId");
    String value = field(input, "body", 500);
    filter(value);
    var comment = map("contentId", id, "body", value, "authorRole", actor.role(), "status", "published");
    Map<String, Object> target = null;
    if (input.has("replyToId")) {
      target = store.get("community_comment", input.str("replyToId", 1, 64));
      require(id.equals(text(target, "contentId")) && visibleComment(target), 404, "RESOURCE_NOT_FOUND", "被回复内容不存在或不可访问");
      comment.put("replyToId", target.get("id"));
      comment.put("rootId", target.getOrDefault("rootId", target.get("id")));
    }
    comment = store.create("community_comment", actor.id(), null, comment);
    if (target == null) notify(text(item, "ownerId"), "buyer", comment, actor,
        "post".equals(text(item, "type")) ? "评论了你的帖子" : "评论了你的店铺评价");
    else notify(text(target, "ownerId"), text(target, "authorRole"), comment, actor, "回复了你的评论");
    return commentDto(comment, actor);
  }

  public Object deleteComment(String id, AuthService.Actor actor) {
    var comment = store.get("community_comment", id);
    owned(comment, actor);
    comment.put("status", "deleted");
    store.save(comment);
    // 不物理删除回复：根评论失效时整串不可见，单条回复失效时保留其他对话。
    return map("deleted", true);
  }

  public Object reaction(String id, String type, Map<String, Object> body, AuthService.Actor actor) {
    var item = content(id, actor, true);
    require("post".equals(text(item, "type")) && List.of("like", "favorite").contains(type),
        400, "VALIDATION_ERROR", "仅养宠帖子支持点赞或收藏");
    boolean active = new Input(body, "active").bool("active");
    String key = actor.id() + ":" + id + ":" + type;
    var reaction = store.byKey("community_reaction", key);
    String status = active ? "active" : "inactive";
    if (reaction == null) store.create("community_reaction", actor.id(), key,
        map("contentId", id, "type", type, "status", status));
    else { reaction.put("status", status); store.save(reaction); }
    return dto(item, actor);
  }

  public Object notices(AuthService.Actor actor) {
    var notices = store.list("community_notice").stream().filter(n -> mine(n, actor)).toList();
    return map("unreadCount", notices.stream().filter(n -> !Boolean.TRUE.equals(n.get("read"))).count(),
        "items", notices.stream().map(n -> {
          var result = select(n, "id,actorName,message,read,createdAt,contentId,commentId");
          var item = store.find("community_content", text(n, "contentId"));
          var comment = store.find("community_comment", text(n, "commentId"));
          result.put("available", item != null && published(item) && comment != null && visibleComment(comment));
          return result;
        }).toList());
  }

  public Object readNotice(String id, AuthService.Actor actor) {
    var notice = store.get("community_notice", id);
    owned(notice, actor);
    notice.put("read", true); store.save(notice);
    return map("read", true);
  }

  public Object report(Map<String, Object> body, AuthService.Actor actor) {
    var input = new Input(body, "targetId,targetType,reason");
    String type = input.choice("targetType", "content,comment"), id = input.str("targetId", 1, 64);
    if ("content".equals(type)) content(id, actor, true);
    else {
      var comment = store.get("community_comment", id);
      content(text(comment, "contentId"), actor, true);
      require(visibleComment(comment), 404, "RESOURCE_NOT_FOUND", "内容不存在或不可访问");
    }
    return store.create("community_report", actor.id(), null,
        map("targetId", id, "targetType", type, "reason", field(input, "reason", 500), "status", "pending"));
  }

  public Object adminContents(String type, AuthService.Actor actor) {
    return store.list("community_content").stream().filter(c -> type == null || type.equals(text(c, "type")))
        .map(c -> dto(c, actor)).toList();
  }

  public Object adminDetail(String id, AuthService.Actor actor) {
    return dto(store.get("community_content", id), actor);
  }

  public Object hide(String targetType, String id, AuthService.Actor actor) {
    require(List.of("content", "comment").contains(targetType), 400, "VALIDATION_ERROR", "内容类型不正确");
    var item = store.get("content".equals(targetType) ? "community_content" : "community_comment", id);
    require(!"deleted".equals(text(item, "status")), 409, "CONTENT_DELETED", "内容已删除");
    item.put("status", "hidden"); store.save(item);
    store.audit(actor.id(), "community.hide", id);
    return map("hidden", true);
  }

  public Object reports() {
    return store.list("community_report").stream().map(r -> {
      var result = select(r, "id,targetId,targetType,reason,status,action,createdAt");
      var target = store.get("content".equals(text(r, "targetType")) ? "community_content" : "community_comment", text(r, "targetId"));
      result.put("targetBody", target.get("body"));
      result.put("contentId", "content".equals(text(r, "targetType")) ? target.get("id") : target.get("contentId"));
      return result;
    }).toList();
  }

  public Object handleReport(String id, Map<String, Object> body, AuthService.Actor actor) {
    var report = store.get("community_report", id);
    String action = new Input(body, "action").choice("action", "hide,dismiss");
    if ("handled".equals(text(report, "status"))) return map("handled", true);
    var target = store.get("content".equals(text(report, "targetType")) ? "community_content" : "community_comment", text(report, "targetId"));
    if ("hide".equals(action) && !"deleted".equals(text(target, "status"))) hide(text(report, "targetType"), text(report, "targetId"), actor);
    report.put("status", "handled"); report.put("action", action); report.put("handledBy", actor.id());
    store.save(report); store.audit(actor.id(), "community.report.handle", id);
    return map("handled", true);
  }

  public Object words() {
    return store.list("community_word").stream().map(w -> select(w, "id,term,enabled,updatedAt")).toList();
  }

  public Object saveWord(String id, Map<String, Object> body, AuthService.Actor actor) {
    var input = new Input(body, "term,enabled");
    String term = field(input, "term", 80), normalized = normalize(term);
    require(!normalized.isBlank() && normalized.codePointCount(0, normalized.length()) <= 80,
        400, "VALIDATION_ERROR", "词条必须包含有效文字且最多80个字符");
    require(store.list("community_word").stream().noneMatch(w -> normalized.equals(text(w, "normalized")) && !Objects.equals(id, w.get("id"))),
        409, "WORD_EXISTS", "词条已存在");
    var word = id == null ? map() : store.get("community_word", id);
    word.put("term", term); word.put("normalized", normalized); word.put("enabled", input.bool("enabled"));
    if (id == null) word = store.create("community_word", actor.id(), null, word); else store.save(word);
    store.audit(actor.id(), "community.word.save", text(word, "id"));
    return select(word, "id,term,enabled,updatedAt");
  }
}
