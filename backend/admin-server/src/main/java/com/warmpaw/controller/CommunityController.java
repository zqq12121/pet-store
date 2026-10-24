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

/**
 * 社区 HTTP 入口：每条路由显式声明身份，写入复用公共事务锁。
 *
 * <p>调用方同时包含买家（{@code /community/**}）与店长（{@code /admin/community/**}），
 * 路由前缀 {@code /api/v1}。职责仅为参数透传：内容过滤、归属校验、审核逻辑都在
 * {@link CommunityService}，事务、幂等与加锁在 {@link ApiRequestExecutor} +
 * {@code BusinessOperationExecutor} 中完成。
 *
 * <p><b>权限模型</b>：不使用 URL 前缀推断，而是每个方法传 {@link AccessLevel}
 * （见 {@code AccessLevel#check}）：
 *
 * <ul>
 *   <li>{@code PUBLIC} 完全不校验，匿名可访问；
 *   <li>{@code BUYER} 等价 {@code AuthService.role(actor, "buyer")}，管理端令牌 403；
 *   <li>{@code ADMIN} 等价 {@code AuthService.role(actor, "admin")}，<b>员工也会被 403</b>，
 *       所以社区审核只能由店长做。
 * </ul>
 *
 * <p>写请求（非 GET）统一持全局写锁，且 ADMIN 写操作会在锁内二次校验会话；
 * 标记 {@code idempotent} 的写接口必须带 UUID 格式的 {@code Idempotency-Key}，否则 400。
 */
@RestController
@RequestMapping("/api/v1")
public class CommunityController {
  private final ApiRequestExecutor requests;
  private final CommunityService service;

  public CommunityController(ApiRequestExecutor requests, CommunityService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * 把"解析身份 → 校验访问级别 → 事务执行 → 包装响应"收在一处。
   *
   * <p>{@code status} 由调用方显式给出（创建类 201、其余 200），避免靠返回值猜状态码；
   * {@code idempotent} 只对写请求生效，执行器会强制要求 UUID 幂等键并保存响应以便重放。
   */
  private ResponseEntity<Object> run(HttpServletRequest request, Map<String, Object> body,
      AccessLevel access, boolean idempotent, int status, Function<AuthService.Actor, Object> action) {
    return requests.execute(request, body, access, idempotent,
        context -> new OperationResult(status, action.apply(context.actor())));
  }

  /**
   * 内容列表（帖子/评价）。
   *
   * <p>GET {@code /community/contents}。公开浏览支持分类、搜索和分页；个人筛选由业务层追加买家鉴权
   * ——{@code scope=all} 允许匿名，{@code scope=mine|favorites} 会在 Service 内
   * {@code role(actor, "buyer")}，未登录返回 401。
   *
   * <p>约束：{@code type} 只能是 post/review，{@code category} 只能是
   * experience/daily/question（可空），搜索词最多 80 字符，{@code page} 从 1 开始、每页固定 20 条。
   *
   * <p>只读、天然幂等；非法枚举值返回 400。
   *
   * @return 200 与 {@code items/total/page/pageSize}
   */
  @GetMapping("/community/contents")
  public ResponseEntity<Object> list(@RequestParam(defaultValue = "post") String type,
      @RequestParam(required = false) String category, @RequestParam(required = false) String query,
      @RequestParam(defaultValue = "all") String scope, @RequestParam(defaultValue = "1") int page,
      HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.PUBLIC, false, 200,
        actor -> service.list(type, category, query, scope, page, actor));
  }

  /**
   * 内容详情。
   *
   * <p>GET {@code /community/contents/{id}}。访问级别 PUBLIC，但可见性由 Service 判定：
   * 仅 {@code published} 对所有人开放；{@code hidden} 只有作者本人或店长能看，
   * 其余（含已删除）统一返回 404，避免用 403 泄露"这条内容存在"。
   *
   * <p>只读、幂等；匿名访问时 {@code liked/favorited} 等个性化字段按未登录处理。
   */
  @GetMapping("/community/contents/{id}")
  public ResponseEntity<Object> detail(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.PUBLIC, false, 200, actor -> service.detail(id, actor));
  }

  /**
   * 发布帖子或订单评价。
   *
   * <p>POST {@code /community/contents}，权限 BUYER。创建帖子、评价及评论使用幂等键，
   * 避免超时重试重复产生内容和通知——必须带 UUID 格式 {@code Idempotency-Key}，
   * 同键不同 body 返回 409 {@code IDEMPOTENCY_CONFLICT}。
   *
   * <p>字段互斥：{@code type=post} 需要 {@code title}(≤80) 与 {@code category}，不接受 rating/orderId；
   * {@code type=review} 需要 {@code orderId}（本人且已完成的订单，一单一评）与 {@code rating}(1~5)，
   * 不接受 title/category。正文上限 5000（帖子）/1000（评价），按 Unicode 码点计。
   *
   * <p>图片最多 9 张，必须是本人上传、用途 community_image、状态 ready、jpeg/png/webp 且单张 ≤5MB；
   * 命中敏感词库返回 422 {@code COMMUNITY_WORD_BLOCKED}。
   *
   * @return 201 与新建内容视图（含 {@code isOwner=true}）
   */
  @PostMapping("/community/contents")
  public ResponseEntity<Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, true, 201, actor -> service.create(body, actor));
  }

  /**
   * 修改自己发布的内容。
   *
   * <p>PUT {@code /community/contents/{id}}，权限 BUYER，且 Service 内再校验归属
   * （{@code owned}）——不是作者一律 404 而非 403，避免探测他人内容是否存在。
   *
   * <p>可改字段随类型而定：帖子 title/body/category/imageFileIds，评价 body/rating/imageFileIds。
   * 修改隐藏内容不会解除管理员的隐藏状态（不会被作者"改一下"洗白）。
   *
   * <p>未启用幂等键（重复提交的代价只是覆盖同一份内容）；命中词库 422。
   *
   * @return 200 与更新后的内容视图
   */
  @PutMapping("/community/contents/{id}")
  public ResponseEntity<Object> update(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, false, 200, actor -> service.update(id, body, actor));
  }

  /**
   * 删除自己发布的内容（软删除）。
   *
   * <p>DELETE {@code /community/contents/{id}}，权限 BUYER + 作者本人校验。
   * 只把状态改为 {@code deleted}，不物理删除：评价的唯一键依赖这条记录，
   * 删掉会让"该订单已评价"的约束失效，用户就能重复刷评价。
   *
   * <p><b>不幂等</b>：重复删除返回 409 {@code CONTENT_DELETED}（而非成功），调用方应把
   * "已删除"也视为目标已达成，不要引导用户重试。
   *
   * @return 200 与 {@code deleted=true}
   */
  @DeleteMapping("/community/contents/{id}")
  public ResponseEntity<Object> delete(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, actor -> service.deleteContent(id, actor));
  }

  /**
   * 可评价的订单列表。
   *
   * <p>GET {@code /community/eligible-orders}，权限 BUYER。只返回当前买家自己、状态为
   * {@code completed} 的订单，并附 {@code reviewed} 标记——前端据此把已评价的订单置灰，
   * 而不是让用户填完整篇评价才被告知重复。
   *
   * <p>归属来自会话（Service 内按 actor.id 过滤），不接受客户端传用户 ID。
   * 只读、幂等。
   *
   * @return 200 与订单数组（{@code id/orderNo/petName/reviewed}）
   */
  @GetMapping("/community/eligible-orders")
  public ResponseEntity<Object> eligibleOrders(HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, service::eligibleOrders);
  }

  /**
   * 评论列表（公开）。
   *
   * <p>GET {@code /community/contents/{id}/comments}，访问级别 PUBLIC。只返回"可见"评论：
   * 自身 published 且父评论仍 published——根评论被删时整串折叠，避免出现无主回复。
   *
   * <p>内容本身不可见时（hidden/deleted）同样 404，不泄露评论存在。只读、幂等。
   * {@code isOwner/isShop} 供前端决定是否显示删除按钮与"店铺"标识。
   */
  @GetMapping("/community/contents/{id}/comments")
  public ResponseEntity<Object> comments(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.PUBLIC, false, 200, actor -> service.comments(id, actor, false));
  }

  /**
   * 发表评论 / 回复。
   *
   * <p>POST {@code /community/contents/{id}/comments}，权限 BUYER，幂等键必填（201）。
   * 字段只允许 {@code body}(≤500) 与可选的 {@code replyToId}；正文需过敏感词过滤。
   *
   * <p>约束：目标内容必须对当前买家可见（否则 404）；{@code replyToId} 指向的父评论
   * 必须属于同一内容且仍然可见。被回复者若是买家会收到站内通知（自己回复自己不通知）。
   *
   * <p>店长回复店铺评价走 {@code POST /admin/community/contents/{id}/comments}，
   * 两处共用同一 Service 方法，靠角色决定 {@code authorRole}。
   *
   * @return 201 与新建评论
   */
  @PostMapping("/community/contents/{id}/comments")
  public ResponseEntity<Object> comment(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, true, 201, actor -> service.comment(id, body, actor));
  }

  /**
   * 删除自己的评论（软删除）。
   *
   * <p>DELETE {@code /community/comments/{id}}，权限 BUYER + 作者本人校验。
   * 不物理删除回复：根评论失效时整串不可见，单条回复失效时保留其他对话上下文。
   *
   * <p>重复删除返回 409；只读列表会立刻隐藏该条（可见性判断依赖状态字段）。
   *
   * @return 200 与 {@code deleted=true}
   */
  @DeleteMapping("/community/comments/{id}")
  public ResponseEntity<Object> deleteComment(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, actor -> service.deleteComment(id, actor));
  }

  /**
   * 点赞 / 收藏切换（幂等赋值，不是"切换"）。
   *
   * <p>PUT {@code /community/contents/{id}/reactions/{type}}，权限 BUYER。
   * {@code type} 只能是 {@code like} 或 {@code favorite}，且只有 {@code post} 支持
   * （评价不能点赞，返回 400）。body 只有 {@code active} 一个布尔字段：传 true 置为有效、
   * false 置为失效，<b>重复提交同一值结果一致</b>，所以重试安全。
   *
   * <p>目标内容必须对当前买家可见；返回更新后的内容视图（含最新的 liked/favorited 计数）。
   */
  @PutMapping("/community/contents/{id}/reactions/{type}")
  public ResponseEntity<Object> reaction(@PathVariable String id, @PathVariable String type,
      @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, false, 200, actor -> service.reaction(id, type, body, actor));
  }

  /**
   * 举报内容或评论。
   *
   * <p>POST {@code /community/reports}，权限 BUYER，幂等键必填（201）。
   * 字段 {@code targetType=content|comment}、{@code targetId}、{@code reason}(≤500)。
   *
   * <p>被举报对象必须对举报人可见，否则 404——否则可以用举报接口探测隐藏内容是否存在。
   * 举报落库为 {@code pending}，由店长在后台处理（hide/dismiss），不自动下线内容。
   *
   * <p>幂等：同键重放返回首次结果；不同键重复举报目前会各自落一条记录（业务上不视为错误）。
   *
   * @return 201 与新建举报记录
   */
  @PostMapping("/community/reports")
  public ResponseEntity<Object> report(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.BUYER, true, 201, actor -> service.report(body, actor));
  }

  /**
   * 我的站内通知（含未读数）。
   *
   * <p>GET {@code /community/notices}，权限 BUYER。只返回收件人是当前买家的通知，
   * 收件人来自会话而非请求参数。每条附 {@code available}：原内容或评论已被删除/隐藏时为 false，
   * 前端据此禁用跳转而不是点进一个 404。
   *
   * <p>只读、幂等；返回全量列表（不分页），未读用 {@code unreadCount} 汇总。
   */
  @GetMapping("/community/notices")
  public ResponseEntity<Object> notices(HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, service::notices);
  }

  /**
   * 把一条通知标记为已读。
   *
   * <p>PUT {@code /community/notices/{id}/read}，权限 BUYER + 收件人本人校验
   * （非本人 404，不区分"不存在"与"不是你的"）。
   *
   * <p>幂等：重复调用只是再次写 {@code read=true}，结果不变，可安全重试。
   *
   * @return 200 与 {@code read=true}
   */
  @PutMapping("/community/notices/{id}/read")
  public ResponseEntity<Object> readNotice(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.BUYER, false, 200, actor -> service.readNotice(id, actor));
  }

  /**
   * 后台内容总览（含未发布与已删除）。
   *
   * <p>GET {@code /admin/community/contents?type=post|review}。管理后台内容、举报和词库接口
   * 不允许买家令牌访问：{@link AccessLevel#ADMIN} 要求角色严格等于 {@code admin}，
   * 员工令牌同样 403。
   *
   * <p>与买家列表不同，这里不做可见性过滤也不分页，便于店长逐条审核；{@code type} 为空表示全部。
   * 只读、幂等。
   *
   * @return 200 与内容数组（含 status 字段）
   */
  @GetMapping("/admin/community/contents")
  public ResponseEntity<Object> adminContents(@RequestParam(required = false) String type, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.adminContents(type, actor));
  }

  /**
   * 后台内容详情（不做可见性过滤，可查看 hidden/deleted）。
   *
   * <p>GET {@code /admin/community/contents/{id}}，权限 ADMIN（员工 403）。
   * 用于审核上下文：能看到正文、状态与作者，但不返回举报记录——那是
   * {@code /admin/community/reports} 的职责。
   *
   * <p>id 不存在时 404；只读、幂等。
   */
  @GetMapping("/admin/community/contents/{id}")
  public ResponseEntity<Object> adminDetail(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.adminDetail(id, actor));
  }

  /**
   * 审核视角的评论列表。
   *
   * <p>GET {@code /admin/community/contents/{id}/comments}，权限 ADMIN，调用
   * {@code comments(id, actor, true)} 的"审核模式"：包含被隐藏的评论，仅排除已删除的，
   * 且不要求内容本身对买家可见——这样下架内容后仍能追溯当时的对话。
   *
   * <p>只读、幂等。
   */
  @GetMapping("/admin/community/contents/{id}/comments")
  public ResponseEntity<Object> adminComments(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.comments(id, actor, true));
  }

  /**
   * 店铺回复买家评价。
   *
   * <p>POST {@code /admin/community/contents/{id}/comments}，权限 ADMIN，幂等键必填（201）。
   * Service 对店长有一条额外限制：<b>只能回复 {@code type=review} 的内容</b>，
   * 对帖子回复会 403 {@code FORBIDDEN}（店铺的公开身份只用于回应针对自己的评价）。
   *
   * <p>评论的 {@code authorRole} 取自会话，因此展示为"店铺"；被回复的买家会收到站内通知。
   * 字段上限 500 字，需过敏感词过滤。
   *
   * @return 201 与新建回复
   */
  @PostMapping("/admin/community/contents/{id}/comments")
  public ResponseEntity<Object> shopReply(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.ADMIN, true, 201, actor -> service.comment(id, body, actor));
  }

  /**
   * 删除店铺自己发布的回复。
   *
   * <p>DELETE {@code /admin/community/comments/{id}}，权限 ADMIN。注意它复用了
   * {@code deleteComment} 的"作者本人"校验：店长只能删 {@code ownerId} 是自己的那条回复，
   * <b>不能借此删除买家的评论</b>——要处理买家内容请用 hide 接口。
   *
   * <p>软删除，重复删除 409；目标不存在或不属于自己时 404。
   *
   * @return 200 与 {@code deleted=true}
   */
  @DeleteMapping("/admin/community/comments/{id}")
  public ResponseEntity<Object> deleteShopReply(@PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.deleteComment(id, actor));
  }

  /**
   * 隐藏内容或评论（审核动作，不改删除状态）。
   *
   * <p>POST {@code /admin/community/{type}/{id}/hide}，权限 ADMIN。
   * {@code type=content|comment} 之外的取值 400；目标已是 {@code deleted} 时 409
   * （已删除无需再隐藏）。隐藏后对买家不可见，但记录与关联数据保留，可追溯。
   *
   * <p>幂等赋值：重复隐藏结果相同，仍会再写一次并追加审计。
   *
   * @return 200 与 {@code hidden=true}
   */
  @PostMapping("/admin/community/{type}/{id}/hide")
  public ResponseEntity<Object> hide(@PathVariable String type, @PathVariable String id, HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.hide(type, id, actor));
  }

  /**
   * 举报处理列表。
   *
   * <p>GET {@code /admin/community/reports}，权限 ADMIN。返回全部举报（含已处理），
   * 并附被举报对象的正文与所属内容 id，便于不跳页即可判断。
   *
   * <p>注意 Service 方法不接收 actor，<b>权限完全由本处的 AccessLevel 把关</b>；
   * 若日后新增调用方，必须自己补鉴权。只读、幂等。
   */
  @GetMapping("/admin/community/reports")
  public ResponseEntity<Object> reports(HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.reports());
  }

  /**
   * 处理举报：隐藏被举报对象或驳回。
   *
   * <p>PUT {@code /admin/community/reports/{id}}，权限 ADMIN。body 只有
   * {@code action=hide|dismiss}。hide 会顺带把目标置为 hidden（目标已删除则跳过，不报错），
   * 随后记录 {@code status=handled}、{@code action}、{@code handledBy} 并写审计。
   *
   * <p><b>幂等</b>：已处理的举报直接返回 {@code handled=true}，不再重复执行隐藏动作。
   *
   * @return 200 与 {@code handled=true}
   */
  @PutMapping("/admin/community/reports/{id}")
  public ResponseEntity<Object> handleReport(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.ADMIN, false, 200, actor -> service.handleReport(id, body, actor));
  }

  /**
   * 敏感词库列表。
   *
   * <p>GET {@code /admin/community/words}，权限 ADMIN。只返回 id/term/enabled/updatedAt，
   * 不暴露归一化后的匹配形式（{@code normalized}）——那是内部实现细节。
   *
   * <p>只读、幂等。
   */
  @GetMapping("/admin/community/words")
  public ResponseEntity<Object> words(HttpServletRequest request) {
    return run(request, Map.of(), AccessLevel.ADMIN, false, 200, actor -> service.words());
  }

  /**
   * 新增敏感词。
   *
   * <p>POST {@code /admin/community/words}，权限 ADMIN，成功 201。字段 {@code term}(≤80)、
   * {@code enabled}；入库前做 NFKC 归一化并去空白/标点，用于比对变体写法。
   *
   * <p>非幂等：重复提交同一词会因归一化结果已存在而返回 409 {@code WORD_EXISTS}——
   * 客户端把 409 当成功处理即可，无需重试。
   *
   * @return 201 与新建词条
   */
  @PostMapping("/admin/community/words")
  public ResponseEntity<Object> createWord(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.ADMIN, false, 201, actor -> service.saveWord(null, body, actor));
  }

  /**
   * 修改或启用/停用敏感词。
   *
   * <p>PUT {@code /admin/community/words/{id}}，权限 ADMIN。同样只接受 {@code term} 与
   * {@code enabled}，归一化后若与其它词条重复（排除自身）返回 409 {@code WORD_EXISTS}。
   *
   * <p>幂等：重复提交相同内容结果一致（但每次都会刷新 updatedAt 并写审计）。
   *
   * @return 200 与更新后的词条
   */
  @PutMapping("/admin/community/words/{id}")
  public ResponseEntity<Object> updateWord(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return run(request, body, AccessLevel.ADMIN, false, 200, actor -> service.saveWord(id, body, actor));
  }
}
