package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 单店社区业务：复用事务执行器和关系表，公开 DTO 不包含手机号或订单详情。
 *
 * <p><b>边界</b>：本类只做"内容层的可见性、归属与审核"，不开启事务、不加锁——调用方
 * （{@code CommunityController} → {@code ApiRequestExecutor}）已经持全局写锁并处在事务中，
 * 这里的每个写方法都假定自己运行在其内。也不负责文件上传（{@link FileService} 只管
 * "这张图归谁、用途对不对"），不负责把通知投递出去（只写 {@code community_notice} 表）。
 *
 * <p><b>内容模型</b>：{@code community_content} 同时承载养宠帖子（{@code post}）与订单评价
 * （{@code review}），靠 {@code type} 区分。评价以 {@code orderId} 作业务键，所以
 * "一单一评"是<b>数据库层的约束</b>，不是应用层的一次查询（见 {@link #create}）。
 *
 * <p><b>三种可见状态</b>，判定集中在 {@link #content} 与 {@link #published}：
 *
 * <ul>
 *   <li>{@code published} —— 所有人可见，匿名也能读列表与详情；
 *   <li>{@code hidden} —— 店长下架。只有作者本人与店长在"非公开视角"（{@code publicOnly=false}）
 *       能看到；一旦走公开视角（评论、点赞、举报），作者自己也读不到，这是刻意收紧的；
 *   <li>{@code deleted} —— 作者软删。对所有人 404，连作者也不能再读，记录只用于去重与审计。
 * </ul>
 *
 * <p><b>权限差异</b>：买家只能改/删自己的内容（{@link #owned} 越权一律 404，不泄露存在性）；
 * 店长的能力只有隐藏、处理举报、维护敏感词三项，<b>不能代作者修改或删除内容</b>。
 * 店铺回复只允许出现在 {@code type=review} 上，评论的 {@code authorRole} 取自会话，
 * 所以"店铺"这个身份无法被买家伪造。
 *
 * <p><b>审核只有一道</b>：发布即 {@code published}，没有人工预审队列；唯一闸门是
 * {@link #filter} 的敏感词命中（422 {@code COMMUNITY_WORD_BLOCKED}）。因此下架是事后动作，
 * 依赖举报 + 店长处置，而不是发帖时拦截。
 */
@Service
public class CommunityService {
  private final BusinessRepository store;

  public CommunityService(BusinessRepository store) {
    this.store = store;
  }

  /**
   * 是否为店长。员工（{@code staff}）在这里为 false——社区审核按控制器声明的 ADMIN 级别，
   * 只认店长，所以"谁下架了内容"永远是店主账号。
   */
  private boolean admin(AuthService.Actor actor) {
    return actor != null && "admin".equals(actor.role());
  }

  /** 归属判定只看 {@code ownerId}；{@code actor} 为 null（匿名浏览）时恒为 false，调用点不必重复判空。 */
  private boolean mine(Map<String, Object> entity, AuthService.Actor actor) {
    return actor != null && actor.id().equals(text(entity, "ownerId"));
  }

  /** 只有 {@code published} 算公开可见；{@code hidden}/{@code deleted}/{@code ready} 都不算。 */
  private boolean published(Map<String, Object> entity) {
    return "published".equals(text(entity, "status"));
  }

  /**
   * 取内容并判定可见性，不可见时统一 404（不用 403，避免泄露"这条内容存在"）。
   *
   * <p>两个参数共同决定放行条件：
   *
   * <ul>
   *   <li>{@code publicOnly=true}：只认 {@code published}。评论、点赞、举报走这条路，
   *       连作者都读不到自己的隐藏内容——否则下架之后作者还能继续给自己的帖子刷互动；
   *   <li>{@code publicOnly=false}：作者本人或店长额外可读 {@code hidden}，但仍排除
   *       {@code deleted}，软删之后内容对任何人都不再取回。
   * </ul>
   *
   * <p>{@code &&} 的优先级高于 {@code ||}，所以"作者/店长"这一支不会绕过 {@code deleted} 判断。
   */
  private Map<String, Object> content(String id, AuthService.Actor actor, boolean publicOnly) {
    var item = store.get("community_content", id);
    require(published(item) || !publicOnly && (mine(item, actor) || admin(actor))
        && !"deleted".equals(text(item, "status")), 404, "RESOURCE_NOT_FOUND", "内容不存在或不可访问");
    return item;
  }

  /**
   * 写操作前的归属校验：非作者 404（不暴露存在性），已删除 409（这是状态冲突，
   * 与"无权访问"区分开，前端才能提示"内容已删除"而不是"找不到"）。
   *
   * <p>店长在这里没有特权——与 {@link #content} 不同，写路径只认作者本人。这样店长误用
   * 删除接口时会被挡住，而不是顺手删掉买家的内容；要处置请走 {@link #hide}。
   */
  private void owned(Map<String, Object> item, AuthService.Actor actor) {
    require(mine(item, actor), 404, "RESOURCE_NOT_FOUND", "内容不存在或不可访问");
    require(!"deleted".equals(text(item, "status")), 409, "CONTENT_DELETED", "内容已删除");
  }

  /**
   * 按 Unicode 字符计数，与前端 Array.from 一致，不把一个 emoji 计算成两位。
   *
   * <p>同时承担"非空 + 类型必须是字符串"的校验，并且 {@code strip()} 掉首尾空白——
   * 只输入空格的内容不该占一个楼层。{@code label} 用中文名拼进错误信息，让用户知道
   * 是"标题"还是"正文"不合规；未列出的字段回落成键名，新增字段时不会漏翻译。
   */
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

  /**
   * 敏感词比对前的归一化：NFKC（全角转半角）→ 转小写 → 去掉空白、分隔符、控制符与标点
   * （对应正则里的 {@code \s}、{@code \p{Z}}、{@code \p{Cf}}、{@code \p{P}}）。
   *
   * <p>目的是让"傻 逼""傻＊逼""Ｓ Ｂ"这类变体落到同一个可比较形式；{@code \p{Cf}} 覆盖零宽字符，
   * 而那正是绕过关键词过滤最常用的手法。代价是会误伤跨界情况——去掉空白后两个正常词可能被
   * 连成一个敏感词，这里选择"宁可错杀"：漏放的代价（脏内容上线）远大于误拦（用户改一句话）。
   */
  private String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
        .replaceAll("[\\s\\p{Z}\\p{Cf}\\p{P}]", "");
  }

  /**
   * 敏感词过滤，命中即 422 {@code COMMUNITY_WORD_BLOCKED}。
   *
   * <p>做法是把多个字段用换行拼成一个整体、归一化后做 {@code contains} 子串匹配——不分词，
   * 所以跨字段的拼接处也可能命中（这正是先 join 再匹配的原因）。词库存的是预先算好的
   * {@code normalized} 字段，运行时不必再归一化词表；只取 {@code enabled=true} 的词条，
   * 停用就等于临时下线而不必删词。
   *
   * <p>策略是"命中即拒"而不是"替换成星号"：脏内容根本不进库，事后不需要再清洗。
   */
  private void filter(String... values) {
    String candidate = normalize(String.join("\n", values));
    boolean rejected = store.list("community_word").stream()
        .filter(w -> Boolean.TRUE.equals(w.get("enabled")))
        .anyMatch(w -> candidate.contains(text(w, "normalized")));
    require(!rejected, 422, "COMMUNITY_WORD_BLOCKED", "内容包含不当词汇，请修改后再提交");
  }

  /**
   * 校验并返回图片文件 id 列表（0~9 张）。
   *
   * <p>四个条件全部满足才放行：文件属于当前买家、用途必须是 {@code community_image}、
   * 状态 {@code ready}、MIME 在 jpeg/png/webp 之内且不超过 5 MB。
   *
   * <p>这里<b>不复用</b> {@code CatalogReader.asset(...)}：那个方法校验的是"附件与订单/用途匹配"，
   * 而社区图片是买家自己的公开素材，没有订单可挂，校验语义不同。
   *
   * <p>重复 id 直接 400：同一张图出现两次只会在详情页渲染出重复图片，没有正当用途。
   */
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

  /**
   * 展示名解析：店长统一显示为"店铺"（不暴露具体店员是谁），买家取昵称。
   *
   * <p>账号查不到时回落成"宠友"而不是抛错——内容还是要展示，用户注销不该让整条帖子变错误页。
   * {@code find} 而不是 {@code get}，正是为了允许"账号已不存在"。
   */
  private String name(String id, String role) {
    if ("admin".equals(role)) return "店铺";
    var user = store.find("user", id);
    return user == null ? "宠友" : Objects.toString(user.get("nickname"), "宠友");
  }

  /** 字段白名单投影：只复制列出的键，避免把 {@code ownerId}、{@code orderId} 等内部字段带出去。 */
  private Map<String, Object> select(Map<String, Object> item, String fields) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String key : fields.split(",")) result.put(key, item.get(key));
    return result;
  }

  /**
   * 内容视图（买家/公开视角）。
   *
   * <p>不含手机号、不含 {@code ownerId}、不含内部状态流转字段；{@code orderId} 只在作者本人
   * 或店长查看时补充——它说明"这是哪一单的评价"，对旁人属于交易信息。
   *
   * <p>点赞数 / 是否已赞 / 是否已收藏都是<b>现算</b>的：每次都扫一遍 {@code community_reaction}。
   * 单店数据量下可接受，换来的是不需要维护计数缓存，也不会出现"计数与明细不一致"。
   * 匿名访问时 {@link #mine} 恒为 false，个性化字段自然全是 false。
   */
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

  /**
   * 内容列表（帖子/评价），公开可读，支持分类、关键词与三种 scope。
   *
   * <p>参数校验先于身份校验：{@code type}/{@code scope}/{@code page}/{@code category} 非法一律 400，
   * 于是"未登录 + 参数错"得到的是 400 而不是 401，前端可据此区分"代码写错"和"没登录"。
   *
   * <p>{@code scope=all} 允许匿名；{@code mine}/{@code favorites} 必须买家身份，
   * 管理端令牌会被 {@code role(actor,"buyer")} 挡成 403。
   *
   * <p>三种 scope 的可见性并不相同，这是最容易踩的地方：
   *
   * <ul>
   *   <li>{@code all} —— 只看 {@code published}；
   *   <li>{@code mine} —— 额外包含 {@code hidden}（作者要知道自己哪条被下架了），仍排除 {@code deleted}；
   *   <li>{@code favorites} —— 只认 {@code published}，收藏过的内容一旦被下架就从列表里消失。
   * </ul>
   *
   * <p>搜索是大小写不敏感的 {@code contains}，只在标题+正文里找，不搜作者名。
   * 分页在内存里做（{@code skip/limit}），与仓库层"整表读入"的实现保持一致。
   */
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

  /** 详情与列表共用 {@link #dto}，但走 {@code publicOnly=false}，所以作者与店长读 {@code hidden} 不会被挡。 */
  public Map<String, Object> detail(String id, AuthService.Actor actor) {
    return dto(content(id, actor, false), actor);
  }

  /**
   * 评价唯一键不随软删除释放，事务锁与数据库约束共同防止重复和并发评价。
   *
   * <p><b>两类内容字段互斥</b>：{@code post} 要 title/category、拒绝 rating/orderId；
   * {@code review} 要 orderId/rating、拒绝 title/category。互斥校验是双向的，不是"忽略多余字段"
   * ——前端把两种表单的默认值一起提交时会立刻 400，而不是悄悄存成半条评价。
   *
   * <p><b>评价资格</b>：订单必须属于本人且状态为 {@code completed}（完成到店交付）。
   * 唯一性用 {@code store.create(..., key=orderId)} 表达——业务键落在库里，所以软删除后依然占用，
   * 重复评价返回 409 {@code ORDER_ALREADY_REVIEWED}，删了也换不回第二次机会。
   *
   * <p>正文上限按类型区分（帖子 5000、评价 1000），发布即 {@code published}，没有预审。
   * 顺序上先做字段互斥与资格校验、再校验图片、最后过敏感词，让最便宜的检查先失败。
   */
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

  /**
   * 作者修改自己的内容；允许的字段随类型切换（帖子不能改 rating，评价不能改 title）。
   *
   * <p>{@code orderId} 不在任何白名单里，所以评价不能"挪单"——否则同一订单可以借修改
   * 把评价搬到另一只宠物名下，而那条订单的唯一键还占着。
   *
   * <p>不校验 {@code published}：被隐藏的内容作者仍可修改（比如删掉违规措辞），
   * 但改完<b>不会自动恢复可见</b>，状态原样保留——否则"改一个字"就能绕过店长的下架。
   */
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

  /** 软删除：只把状态置 {@code deleted}，不物理删行——评价的唯一键依赖它（见 {@link #create}）。 */
  public Object deleteContent(String id, AuthService.Actor actor) {
    var item = store.get("community_content", id);
    owned(item, actor);
    item.put("status", "deleted");
    store.save(item);
    return map("deleted", true);
  }

  /**
   * 可评价订单列表：本人 + {@code completed}，并附 {@code reviewed} 供前端提前置灰。
   *
   * <p>{@code reviewed} 用业务键直查（{@code byKey(orderId)}），所以<b>已软删的评价同样算已评价</b>，
   * 与 {@link #create} 的 409 判定使用同一口径，不会出现"列表说能评、提交却说重复"。
   *
   * <p>订单里没有商品快照时回落成"到店消费"，这是一个服务类订单的兜底展示文案。
   */
  public List<Map<String, Object>> eligibleOrders(AuthService.Actor actor) {
    return store.list("order").stream().filter(o -> mine(o, actor) && "completed".equals(text(o, "status")))
        .map(o -> {
          var prior = store.byKey("community_content", text(o, "id"));
          return map("id", o.get("id"), "orderNo", o.get("orderNo"),
              "petName", object(o, "product").getOrDefault("name", "到店消费"),
              "reviewed", prior != null);
        }).toList();
  }

  /**
   * 评论可见性：自身 {@code published}，并且父评论（{@code rootId}）也 {@code published}。
   *
   * <p>根评论被删/被隐藏时整串回复一起折叠，避免出现"回复挂在一层看不见的楼上"；
   * 单条回复失效只影响它自己，其余对话照常显示。{@code rootId} 为空表示这是一级评论。
   */
  private boolean visibleComment(Map<String, Object> comment) {
    if (!published(comment)) return false;
    String rootId = text(comment, "rootId");
    return rootId == null || published(store.get("community_comment", rootId));
  }

  /**
   * 评论视图：补作者展示名与 {@code isShop}，并延迟解析被回复者的名字。
   *
   * <p>{@code authorName} 用评论自带的 {@code authorRole} 解析，而不是重新查账号角色——
   * 员工可能已离职、账号可能已改角色，展示的应该是"当时以什么身份说的"。
   *
   * <p>被回复的评论若已不可见，{@code replyToName} 回落成"原回复已删除"而不是留空：
   * 让读者知道这里原本有一条回复，而不是以为界面出错。
   */
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

  /**
   * 评论列表，按创建时间升序（楼中楼由前端用 {@code rootId}/{@code replyToId} 组装）。
   *
   * <p>两种视角，性质完全不同：
   *
   * <ul>
   *   <li>{@code moderation=false}：先校验内容对当前人可见，再只返回 {@link #visibleComment} 通过的。
   *       注意内容被隐藏时作者仍能看到评论（{@code content(...,false)} 放行了作者），匿名者不行；
   *   <li>{@code moderation=true}：只排除 {@code deleted}，包含 hidden，并且<b>不要求内容本身可见</b>
   *       ——下架之后仍要能追溯当时的对话，这是审核与申诉的依据。
   * </ul>
   *
   * <p>{@code moderation} 分支用的是 {@code store.get}（不存在即 404），这是一次存在性检查，
   * 不是可见性检查，别把它当成权限校验。
   */
  public List<Map<String, Object>> comments(String id, AuthService.Actor actor, boolean moderation) {
    if (moderation) store.get("community_content", id); else content(id, actor, false);
    return store.list("community_comment").stream().filter(c -> id.equals(text(c, "contentId")))
        .filter(c -> moderation ? !"deleted".equals(text(c, "status")) : visibleComment(c))
        .sorted(Comparator.comparing(c -> text(c, "createdAt")))
        .map(c -> commentDto(c, actor)).toList();
  }

  /**
   * 写一条站内通知，业务键是 {@code commentId:recipient}，同一条评论对同一个人只会有一条。
   *
   * <p>两类不发：收件人不是买家（店长不需要站内信），以及收件人就是操作者（自己回复自己不提醒）。
   *
   * <p>{@code actorName} 在写入时固化，对方之后改名也不会改动历史通知——通知记的是
   * "当时谁做了什么"，不是"现在这个人叫什么"。
   */
  private void notify(String recipient, String role, Map<String, Object> comment,
      AuthService.Actor actor, String message) {
    if (!"buyer".equals(role) || recipient.equals(actor.id())) return;
    store.create("community_notice", recipient, text(comment, "id") + ":" + recipient,
        map("contentId", comment.get("contentId"), "commentId", comment.get("id"),
            "actorName", name(actor.id(), actor.role()), "message", message, "read", false));
  }

  /**
   * 发表评论或回复。
   *
   * <p><b>店长限制</b>：店长只能回复 {@code type=review} 的内容（403 {@code FORBIDDEN}），
   * 因为"店铺"这个公开身份只用于回应针对自己的评价，不该出现在买家之间的帖子里。
   * 买家则可以对任何对自己可见的内容评论——这里是 {@code publicOnly=true}，
   * 所以连自己被隐藏的帖子也评论不了。
   *
   * <p>{@code replyToId} 必须指向<b>同一内容下、仍然可见</b>的评论，否则 404：既能防止跨内容串楼，
   * 也能防止用回复接口探测隐藏评论。{@code rootId} 继承被回复者的 rootId（没有则用它自己的 id），
   * 保证二级以上回复都能归到同一棵楼里。
   *
   * <p>通知只发给"直接对象"：一级评论通知内容作者，回复通知被回复者，不做全链路通知。
   */
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

  /**
   * 软删除评论。
   *
   * <p>店长复用的也是这个方法，所以<b>店长只能删自己发的店铺回复</b>；删买家的评论要走
   * {@link #hide}——"作者撤回"与"平台处置"是两种不同的事实，不能共用一个状态。
   */
  public Object deleteComment(String id, AuthService.Actor actor) {
    var comment = store.get("community_comment", id);
    owned(comment, actor);
    comment.put("status", "deleted");
    store.save(comment);
    // 不物理删除回复：根评论失效时整串不可见，单条回复失效时保留其他对话。
    return map("deleted", true);
  }

  /**
   * 点赞 / 收藏：PUT 的是目标状态（{@code active=true/false}），不是 toggle。
   *
   * <p>业务键 {@code 用户:内容:类型}，同一人对同一内容同一类型永远只有一行，重复提交结果一致，
   * 所以超时重试、双击都不会把状态点反。取消时置 {@code inactive} 而不是删行：
   * 保留"曾经赞过"的事实，再次点赞也复用同一行。
   *
   * <p>只允许 {@code post}：评价是交易反馈，让点赞数影响排序会扭曲评价体系，故直接 400。
   * 目标内容必须公开可见（{@code publicOnly=true}），下架内容不能被点赞。
   *
   * <p>返回更新后的内容视图（含最新计数），前端不必再拉一次详情。
   */
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

  /**
   * 我的站内通知（不分页）与未读数。
   *
   * <p>收件人来自会话，不接受任何参数。每条附 {@code available}：原内容或评论已被隐藏/删除时为
   * false，前端据此禁用跳转——通知本身不删，用户仍能看到"谁在什么时候回复过你"。
   *
   * <p>这里用 {@code find} 而不是 {@code get}：评论被清理后通知应该退化成"不可点击"，
   * 而不是让整个通知列表 404。
   */
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

  /** 标记已读；归属校验复用 {@link #owned}（非收件人 404），重复调用无副作用。 */
  public Object readNotice(String id, AuthService.Actor actor) {
    var notice = store.get("community_notice", id);
    owned(notice, actor);
    notice.put("read", true); store.save(notice);
    return map("read", true);
  }

  /**
   * 举报内容或评论，落库为 {@code pending}，<b>不自动下架</b>——处置权在店长手上。
   *
   * <p>被举报对象必须对举报人可见：内容走 {@link #content}，评论还额外要求所属内容可见
   * 且 {@link #visibleComment} 通过。否则这个接口就成了"探测隐藏内容是否存在"的工具。
   *
   * <p>不做去重：同一个人重复举报会各落一条，由店长按 {@code handled} 自行归并
   * （业务上不视为错误），这样举报次数本身还能作为严重程度的参考。
   */
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

  /** 后台总览：不过滤可见性、不分页，{@code type} 为空表示全部；{@code deleted} 也会出现在结果里。 */
  public Object adminContents(String type, AuthService.Actor actor) {
    return store.list("community_content").stream().filter(c -> type == null || type.equals(text(c, "type")))
        .map(c -> dto(c, actor)).toList();
  }

  /** 后台详情：不做可见性过滤，id 不存在时由 {@code store.get} 抛 404。 */
  public Object adminDetail(String id, AuthService.Actor actor) {
    return dto(store.get("community_content", id), actor);
  }

  /**
   * 店长隐藏内容或评论（审核动作）。
   *
   * <p>只把状态改成 {@code hidden}，不动 {@code deleted}，也不删除关联数据：隐藏可追溯、可复核，
   * 与作者自删是两种不同的事实。已 {@code deleted} 的目标返回 409——删除是终态，再隐藏没有意义。
   *
   * <p>幂等赋值：重复隐藏结果相同，每次都会再写一次并追加一条审计。
   */
  public Object hide(String targetType, String id, AuthService.Actor actor) {
    require(List.of("content", "comment").contains(targetType), 400, "VALIDATION_ERROR", "内容类型不正确");
    var item = store.get("content".equals(targetType) ? "community_content" : "community_comment", id);
    require(!"deleted".equals(text(item, "status")), 409, "CONTENT_DELETED", "内容已删除");
    item.put("status", "hidden"); store.save(item);
    store.audit(actor.id(), "community.hide", id);
    return map("hidden", true);
  }

  /**
   * 举报列表（含已处理），内联被举报对象的正文与所属内容 id，便于一屏内判断。
   *
   * <p><b>本方法不接收 actor</b>，权限完全由控制器声明的 ADMIN 级别把关；日后新增调用方时必须
   * 自己补鉴权，别把"没人拦"当成"已经拦过"。
   *
   * <p>目标对象用 {@code store.get} 读取：正常业务里目标只会被软删、不会消失，
   * 若真出现行缺失，这里会整表 404 而不是静默跳过——那说明数据已经被外部改坏，值得暴露。
   */
  public Object reports() {
    return store.list("community_report").stream().map(r -> {
      var result = select(r, "id,targetId,targetType,reason,status,action,createdAt");
      var target = store.get("content".equals(text(r, "targetType")) ? "community_content" : "community_comment", text(r, "targetId"));
      result.put("targetBody", target.get("body"));
      result.put("contentId", "content".equals(text(r, "targetType")) ? target.get("id") : target.get("contentId"));
      return result;
    }).toList();
  }

  /**
   * 处理举报：{@code hide} 顺带隐藏目标，{@code dismiss} 仅归档。
   *
   * <p>幂等：已 {@code handled} 的直接返回，不重复执行隐藏动作——但 {@code action} 仍会先被校验，
   * 非法取值依旧是 400，不会因为"已处理"就跳过参数检查。目标已删除时跳过隐藏、不报错：
   * 删除本身已经达到下架效果。
   *
   * <p>隐藏动作会调用 {@link #hide}，因此一次处理会写两条审计（{@code community.hide} 与
   * {@code community.report.handle}），事后可以从"内容"和"举报单"两个角度追溯。
   */
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

  /** 词库列表：不返回 {@code normalized}，那是内部匹配形式，暴露出去等于告诉对方怎么绕过过滤。 */
  public Object words() {
    return store.list("community_word").stream().map(w -> select(w, "id,term,enabled,updatedAt")).toList();
  }

  /**
   * 新增（{@code id == null}）或修改敏感词条。
   *
   * <p>两个字段都存：{@code term} 是原文用于展示，{@code normalized} 是归一化形式用于匹配，
   * 这样运行时不必反复归一化整张词表。归一化后为空（比如只输入了标点或空格）直接 400，
   * 否则会得到一个匹配一切输入的空串词条。
   *
   * <p>唯一性按归一化结果判定并排除自身，所以"傻逼"与"傻 逼"不能同时存在；
   * 冲突返回 409 {@code WORD_EXISTS}，客户端可以当作"已经有了"。
   *
   * <p>{@code enabled=false} 是软下线：匹配时跳过，不必删词条，历史审计也不受影响。
   */
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
