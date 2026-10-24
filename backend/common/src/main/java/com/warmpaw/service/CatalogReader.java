package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 共享目录读取与快照投影；订单事务使用同库读取，不包含商品、门店或协议写入。
 *
 * <p><b>只读</b>：本类没有任何写入路径，只做"从库里读实体 → 裁剪成对外视图"。之所以要有这一层，
 * 是因为 admin-server 与 order-server 都要展示商品/门店，但两边都不该各自手写投影：
 * 一旦某处忘了剔除内部字段（成本价、审核备注、附件归属、店铺运营参数），就会顺着响应体漏给买家。
 * 统一收口在这里，漏字段只可能漏一处。
 *
 * <p><b>三种视图的边界</b>：
 *
 * <ul>
 *   <li>{@link #card} —— 列表卡片，只够渲染商品格子；买家列表用它，不含购买资格判定；
 *   <li>{@link #detail} —— 详情页，在卡片基础上补描述、图片、检疫证明、门店公开信息与
 *       {@code purchaseAllowed}；
 *   <li>{@link #adminPet} —— 后台视图，反过来是"几乎全字段 + 附件元数据"，供店长编辑，
 *       但仍剔除 {@code ownerId}（归属是内部概念，后台不需要也不该改）。
 * </ul>
 *
 * <p><b>投影裁剪为什么必须显式列出字段</b>：{@code pet}/{@code shop} 的实体是 Map，
 * 直接返回等于把数据库列名连同运营参数一起下发。{@link #select} 用白名单反查，
 * 新增字段时默认<b>不可见</b>——这是安全的默认值；如果反过来用黑名单，任何一次加字段都是一次泄露。
 *
 * <p><b>无 Spring 注解</b>：本类刻意不加 {@code @Service}。admin-server 的
 * {@code CatalogService} 直接<b>继承</b>它；order-server 在启动类里用 {@code @Bean} 手动 {@code new}
 * 一个实例，再注入给订单/预约/售后等服务。<b>任何写方法都不能下沉到本类</b>——
 * 那会让只读的交易服务被迫依赖商品写入逻辑。
 */
public class CatalogReader {
  /** 子类可直接复用；{@code protected} 是为了让 admin/order 两侧的领域服务不必再自建仓储字段。 */
  protected final BusinessRepository store;
  private final com.warmpaw.repository.PetQueries petQueries;

  public CatalogReader(BusinessRepository store, com.warmpaw.repository.PetQueries petQueries) {
    this.store = store;
    this.petQueries = petQueries;
  }

  /**
   * 门店完整实体，仅限后台使用。
   *
   * <p>返回的是未经裁剪的行，含 {@code paymentTimeoutMinutes}/{@code pickupRetentionHours}/
   * {@code exchangeEnabled} 等运营参数，这些直接决定交易规则，不能出现在任何买家可访问的响应里。
   * 公开场景一律走 {@link #publicShop}。
   *
   * <p>门店行不存在时抛 404（由 {@code store.get} 负责）；单店部署固定用 {@code shop_1}。
   */
  public Map<String, Object> shop() {
    return store.get("shop", "shop_1");
  }

  /**
   * 门店的买家可见投影：只保留能印在门店名片上的信息。
   *
   * <p>注意 {@code businessHours} 在这里是<b>原文</b>下发，而预约校验需要的是
   * {@code AppointmentHours.parse} 能识别的严格格式——接口不做格式转换，
   * 店长填错时买家端会看到原文、预约接口会返回 422，两者行为一致。
   */
  public Map<String, Object> publicShop() {
    Map<String, Object> s = shop();
    return select(
        s,
        "id,name,address,latitude,longitude,coordinateSystem,phone,wechat,businessHours,pickupInstructions");
  }

  /**
   * 按逗号分隔的白名单裁剪 Map（反查式投影）。
   *
   * <p>两个容易踩的语义：
   *
   * <ul>
   *   <li>字段<b>一定存在</b>：源里没有的键会以 {@code null} 值出现，前端拿到的是稳定的键集合，
   *       不需要区分"字段不存在"和"字段为空"；
   *   <li>返回 {@link LinkedHashMap}，键顺序 = 白名单书写顺序，响应体字段顺序因此稳定可 diff。
   * </ul>
   *
   * <p>有意不校验 {@code source} 非空：调用方通常已经通过 {@code store.get} 拿到实体，
   * 空指针在这里暴露得比"返回全 null 的卡片"更早、更容易定位。
   */
  public static Map<String, Object> select(Map<String, Object> source, String fields) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String f : fields.split(",")) result.put(f, source.get(f));
    return result;
  }

  /**
   * 取附件并校验"用途 + 归属"，是文件访问的唯一准入点。
   *
   * <p>三重校验，错误码刻意不同：
   *
   * <ol>
   *   <li>{@code purpose} 必须等于调用方声明的用途，且文件状态为 {@code ready}
   *       （上传中/已删除的文件不可引用）——不匹配返回 <b>422</b>，因为这是调用方把文件
   *       放错了业务槽位，属于参数语义错误而非"找不到"；
   *   <li>{@code ownerId} 为 null 表示不校验归属（后台/系统路径）；非 null 则必须匹配，
   *       否则 <b>404</b>；
   *   <li>{@code orderId} 为 null 表示不限定订单；非 null 必须匹配，否则 <b>404</b>。
   * </ol>
   *
   * <p>归属失败统一回 <b>404 而不是 403</b>：403 等于承认"这个文件存在，只是不给你"，
   * 攻击者可以拿它枚举 fileId。404 让越权与不存在不可区分。
   */
  public Map<String, Object> asset(String fileId, String purpose, String owner, String orderId) {
    Map<String, Object> f = store.get("file", fileId);
    require(
        purpose.equals(text(f, "purpose")) && "ready".equals(text(f, "status")),
        422,
        "FILE_PURPOSE_MISMATCH",
        "附件用途不匹配");
    if (owner != null)
      require(owner.equals(text(f, "ownerId")), 404, "RESOURCE_NOT_FOUND", "附件不可访问");
    if (orderId != null)
      require(orderId.equals(text(f, "orderId")), 404, "RESOURCE_NOT_FOUND", "附件不属于此订单");
    return f;
  }

  /**
   * 校验店长提交的检疫证明结构，并逐个确认引用的附件确实属于本店、用途正确。
   *
   * <p>公开图必须 1~5 张（买家看得见，是"这只宠物有检疫"的证据）；原始凭证可以不给
   * （{@code has} 判断），给了则 0~5 张——上限 5 是防止单个商品把存储打满。
   *
   * <p>返回 {@link #copy} 的深拷贝而不是原 Map：校验过程借用了入参，返回副本让调用方
   * 可以放心改写（补 id、改状态）而不会反向污染请求体。
   *
   * <p>{@code validUntil} 只校验格式不校验"是否已过期"——录入一张已过期的历史证明是合法的，
   * 过期判定留给 {@link #validQuarantine} 与 {@link #publicQuarantine}。
   */
  public Map<String, Object> quarantine(Map<String, Object> q, String owner) {
    Input in = new Input(q, "certificateNo,publicImageFileIds,originalFileIds,validUntil");
    in.str("certificateNo", 1, 100);
    for (String id : in.ids("publicImageFileIds", 1, 5))
      asset(id, "quarantine_public", owner, null);
    if (in.has("originalFileIds"))
      for (String id : in.ids("originalFileIds", 0, 5))
        asset(id, "quarantine_original", owner, null);
    if (q.get("validUntil") != null) parseDate(in.str("validUntil", 10, 10));
    return copy(q);
  }

  /**
   * 解析 {@code yyyy-MM-dd} 日期。
   *
   * <p>用 {@code LocalDate.parse} 的严格模式：{@code 2026-02-30} 这类"格式对但日期不存在"的值
   * 也会被拒绝。任何解析失败统一转 400 {@code VALIDATION_ERROR}，不把
   * {@code DateTimeParseException} 漏到 500 分支。
   */
  public static LocalDate parseDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (Exception e) {
      throw new ApiException(400, "VALIDATION_ERROR", "日期格式不正确");
    }
  }

  /**
   * 检疫证明是否满足"可售"的最低要求。
   *
   * <p>三个条件：证明存在、至少一张公开图、未过期（{@code validUntil} 为空视为<b>长期有效</b>）。
   *
   * <p>为什么要卡未过期：活体交易的检疫证明有法定期限，过期证明等于没有证明；
   * 而没有公开图则买家无法核验，等于让买家盲买。
   *
   * <p>这里<b>不做</b>附件归属/状态复核——那是写入时 {@link #quarantine} 的职责，
   * 每个读请求再查一遍文件表会把列表接口变成 N+1。
   *
   * <p>日期比较用北京时区的"今天"，而不是 UTC：门店在国内，凌晨 0~8 点用 UTC 会把
   * 当天到期的证明判成"未过期"多卖一天。
   */
  public boolean validQuarantine(Map<String, Object> pet) {
    Map<String, Object> q = object(pet, "quarantine");
    return !q.isEmpty()
        && !strings(q, "publicImageFileIds").isEmpty()
        && (q.get("validUntil") == null
            || !parseDate(text(q, "validUntil"))
                .isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
  }

  /**
   * 检疫证明的买家可见投影。
   *
   * <p>只暴露证号、公开图、有效期；原始凭证（{@code originalFileIds}）与审核备注永不下发。
   *
   * <p>{@code reviewStatus} 这个名字容易误解：它<b>只表示"是否过期"</b>，取值只有
   * {@code valid}/{@code expired}，没有"待审/驳回"——因为写入前已由 {@link #quarantine}
   * 校验过结构，库里不存在未审的中间态。
   *
   * <p>空证明返回 {@code null} 而不是空对象，让前端能用一次判空决定是否渲染整块证明区域。
   *
   * <p>副作用：会按 fileId 逐个 {@code store.get("file", ...)}，文件缺失时直接 404。
   * 这是有意的失败关闭——证明图片取不到时宁可让详情页报错，也不能展示一张残缺的证明。
   */
  public Map<String, Object> publicQuarantine(Map<String, Object> q) {
    if (q.isEmpty()) return null;
    return map(
        "certificateNo",
        q.get("certificateNo"),
        "images",
        strings(q, "publicImageFileIds").stream()
            .map(id -> map("fileId", id, "url", store.get("file", id).get("publicUrl")))
            .toList(),
        "validUntil",
        q.get("validUntil"),
        "reviewStatus",
        q.get("validUntil") != null
                && parseDate(text(q, "validUntil"))
                    .isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))
            ? "expired"
            : "valid");
  }

  /**
   * 是否允许买家下单，四个条件全部满足才算可买。
   *
   * <ol>
   *   <li>状态为 {@code on_sale}（{@code reserved} 已有主、{@code sold}/{@code off} 更不行）；
   *   <li>检疫证明有效（见 {@link #validQuarantine}，没有有效证明的活体不允许交易）；
   *   <li>价格大于 0（0 元单会让支付渠道直接拒单，不如在下单前拦住）；
   *   <li>{@code pet_occupancy} 里没有占用记录（有人在支付窗口内已经锁定了它）。
   * </ol>
   *
   * <p>第 4 条是唯一一次真实数据库访问，因此本方法<b>不适合在列表里逐条调用</b>：
   * 详情页虽然调了两次（{@code purchaseAllowed} 与 {@code purchaseBlockedReason}），
   * 但只针对单只宠物，代价可接受；列表卡片则完全不判断购买资格。
   *
   * <p>这里只是"此刻是否可买"的展示性判断，<b>不构成并发保证</b>：真正的抢占由
   * {@code occupy} 的主键冲突负责，本方法返回 true 之后仍可能下单失败。
   */
  public boolean purchasable(Map<String, Object> pet) {
    return "on_sale".equals(text(pet, "status"))
        && validQuarantine(pet)
        && number(pet, "priceAmount") > 0
        && store.occupation(text(pet, "id")) == null;
  }

  /**
   * 列表卡片视图：买家列表与首页推荐共用的最小字段集。
   *
   * <p>只有 10 个标量字段 + 币种 + 封面图。刻意<b>不含</b>描述、图片列表、检疫证明、
   * 购买资格——列表页一次返回几十条，任何额外的文件查询都会被放大成 N+1。
   *
   * <p><b>月龄会覆盖库里的值</b>：只要实体上有 {@code birthDate}，就用"今天 - 出生日期"
   * 现算。库里存的 {@code age_months} 是写入时的快照，放一个月就会失真；宠物按月龄定价，
   * 展示错误月龄会直接引发纠纷。
   *
   * <p>封面取 {@code imageFileIds} 的第一张。图片顺序由后台维护（第一张即主图），
   * 这里不再排序，保证后台所见即买家所见。
   */
  public Map<String, Object> card(Map<String, Object> p) {
    Map<String, Object> c =
        select(
            p,
            "id,name,category,breed,priceAmount,gender,ageMonths,status,isRecommended,publishedAt");
    c.put("currency", "CNY");
    c.put(
        "coverUrl",
        strings(p, "imageFileIds").isEmpty()
            ? null
            : store.get("file", strings(p, "imageFileIds").getFirst()).get("publicUrl"));
    if (p.get("birthDate") != null)
      c.put(
          "ageMonths",
          ChronoUnit.MONTHS.between(
              parseDate(text(p, "birthDate")), LocalDate.now(ZoneId.of("Asia/Shanghai"))));
    return c;
  }

  /**
   * 详情视图：卡片 + 描述类字段 + 图片/视频 + 检疫证明 + 门店公开信息 + 购买资格。
   *
   * <p>逐层叠加而不是重写一遍字段清单，避免"卡片加了字段、详情忘了加"这类不同步。
   *
   * <p>几个设计点：
   *
   * <ul>
   *   <li>{@code version} 会下发给客户端：下单接口需要它做乐观并发校验，
   *       防止用户看着旧价格提交；
   *   <li>图片带 {@code sortOrder}，前端按它渲染轮播；顺序就是后台维护的顺序；
   *   <li>{@code purchaseBlockedReason} 是一句面向用户的话，不含任何内部状态名——
   *       具体是"已售出"还是"检疫过期"由前端看 {@code status}/证明区域自行呈现，
   *       接口不在这里泄露内部判定细节；
   *   <li>内嵌 {@link #publicShop} 而不是门店实体：详情页只展示门店名片。
   * </ul>
   */
  public Map<String, Object> detail(Map<String, Object> p) {
    Map<String, Object> c = card(p);
    c.putAll(
        select(
            p,
            "version,weightKg,color,personalityTags,vaccineStatus,dewormStatus,description,feedingNotes,healthDescription"));
    List<Map<String, Object>> images = new ArrayList<>();
    int index = 0;
    for (String id : strings(p, "imageFileIds"))
      images.add(
          map("fileId", id, "url", store.get("file", id).get("publicUrl"), "sortOrder", index++));
    c.put("images", images);
    c.put(
        "videoUrl",
        p.get("videoFileId") == null
            ? null
            : store.get("file", text(p, "videoFileId")).get("publicUrl"));
    c.put("quarantine", publicQuarantine(object(p, "quarantine")));
    c.put("shop", publicShop());
    c.put("purchaseAllowed", purchasable(p));
    c.put("purchaseBlockedReason", purchasable(p) ? null : "当前不可购买，请联系店主核实");
    return c;
  }

  /**
   * 买家侧宠物详情入口。
   *
   * <p><b>只有 {@code off} 返回 404</b>：下架商品对外等于不存在。而 {@code sold}/{@code reserved}
   * 仍然正常返回——已售出的详情页是刚需（买家从订单/分享链接点回来要能看到"已售出"），
   * 已预订的也要能展示。这两个状态下 {@code purchaseAllowed} 自然为 false，由详情体表达，
   * 而不是用 404 表达。
   */
  public Map<String, Object> publicPet(String id) {
    Map<String, Object> p = store.get("pet", id);
    require(!"off".equals(text(p, "status")), 404, "RESOURCE_NOT_FOUND", "宠物不存在或已下架");
    return detail(p);
  }

  /**
   * 后台宠物视图：全字段 + 附件元数据，供店长编辑表单回填。
   *
   * <p>它其实是"反向投影"——不给白名单，直接深拷贝全部字段，再<b>摘掉 {@code ownerId}</b>。
   * 原因：后台编辑器会把收到的对象整体回传保存，如果把归属也带上，一次编辑就可能
   * 把归属改成前端传来的任意值；直接不给，等于从契约上消除这条越权路径。
   *
   * <p>{@code assets} 只覆盖 {@code imageFileIds}，<b>不含视频</b>：视频在编辑表单里
   * 只需要一个 fileId，不需要额外的元数据面板。这是有意的取舍，不是遗漏。
   *
   * <p>单个附件字段按 {@code id,originalName,mimeType,sizeBytes,purpose,visibility,status,publicUrl,createdAt}
   * 裁剪，{@code storageKey} 这类存储实现细节绝不下发。
   */
  public Map<String, Object> adminPet(Map<String, Object> p) {
    Map<String, Object> result = copy(p);
    result.remove("ownerId");
    result.put(
        "assets",
        strings(p, "imageFileIds").stream()
            .map(
                id ->
                    select(
                        store.get("file", id),
                        "id,originalName,mimeType,sizeBytes,purpose,visibility,status,publicUrl,createdAt"))
            .toList());
    return result;
  }

  /**
   * 分类与品种筛选项。
   *
   * <p>分类是<b>写死的</b> {@code cat}/{@code dog} 两种，不从库里取——分类是产品定义，
   * 不是可运营数据；这样前端筛选器的结构永远稳定，不会因为某天没有猫可卖就消失。
   *
   * <p>品种来自现存商品去重排序，且排除 {@code off}：筛选器里出现一个筛完为空的品种
   * 是纯粹的体验噪音。
   *
   * <p>代价是每次调用都要 {@code store.list("pet")} 全表加载（见 {@link #home} 的说明），
   * 只适合首页这种低 QPS 的聚合接口。
   */
  public List<Map<String, Object>> categories() {
    return List.of("cat", "dog").stream()
        .map(
            c ->
                map(
                    "code",
                    c,
                    "name",
                    c.equals("cat") ? "猫咪" : "狗狗",
                    "breeds",
                    store.list("pet").stream()
                        .filter(
                            p -> c.equals(text(p, "category")) && !"off".equals(text(p, "status")))
                        .map(p -> text(p, "breed"))
                        .distinct()
                        .sorted()
                        .toList()))
        .toList();
  }

  /**
   * 首页"综合排序"的比较器，四级依次回退：
   *
   * <ol>
   *   <li>推荐的排前面（{@code !TRUE.equals(isRecommended)} 做升序，等于 true 在前——
   *       注意 {@code Boolean.compare} 里 false 更小，所以这里要先取反；
   *   <li>{@code recommendationOrder} 升序（人工指定的推荐位次）；
   *   <li>{@code publishedAt} 倒序（新上架优先）；
   *   <li>{@code id} 倒序兜底，保证结果<b>全序稳定</b>——否则同批次商品每次查询顺序可能不同，
   *       翻页会出现重复/漏项。
   * </ol>
   *
   * <p>{@code publishedAt} 缺失时按空串处理，排在最末，不会抛 NPE。
   */
  private Comparator<Map<String, Object>> comprehensive() {
    return Comparator.<Map<String, Object>, Boolean>comparing(
            p -> !Boolean.TRUE.equals(p.get("isRecommended")))
        .thenComparingLong(p -> number(p, "recommendationOrder"))
        .thenComparing(p -> Objects.toString(p.get("publishedAt"), ""), Comparator.reverseOrder())
        .thenComparing(p -> text(p, "id"), Comparator.reverseOrder());
  }

  /**
   * 首页聚合：banner + 分类 + 推荐 + 最新 + 门店名片，一次请求全部返回。
   *
   * <p><b>一次全表加载</b>：{@code store.list("pet")} 把整张宠物表读进内存再过滤/排序/截断。
   * 对"单店、几百只宠物"的规模是合适的（省掉多次往返，且推荐/最新/分类能复用同一份数据）；
   * 但它不是可扩展的写法，门店数量或 SKU 量级上来后应改成三条带 LIMIT 的 SQL。
   *
   * <p>几处刻意的取舍：
   *
   * <ul>
   *   <li>只取 {@code on_sale}/{@code reserved}：首页是导购入口，已售出/下架的东西不占位；
   *   <li>banner 若指向具体宠物（{@code linkType=pet}），而该宠物已不在上述状态，
   *       整条 banner 会被过滤掉——避免用户点进一个 404；
   *   <li>推荐位最多 8 条，按 {@link #comprehensive} 排；最新位最多 8 条，只按发布时间排；
   *   <li>banner 的图片走 {@code store.get("file", ...)}，文件缺失会 404 而不是静默留空，
   *       让配置错误在首页立刻暴露。
   * </ul>
   */
  public Map<String, Object> home() {
    List<Map<String, Object>> pets =
        store.list("pet").stream()
            .filter(p -> List.of("on_sale", "reserved").contains(text(p, "status")))
            .toList();
    List<Map<String, Object>> banners =
        objects(shop(), "banners").stream()
            .filter(b -> Boolean.TRUE.equals(b.get("enabled")))
            .filter(
                b ->
                    !"pet".equals(text(b, "linkType"))
                        || pets.stream().anyMatch(p -> p.get("id").equals(b.get("petId"))))
            .sorted(Comparator.comparingLong(b -> number(b, "sortOrder")))
            .map(
                b -> {
                  Map<String, Object> result =
                      select(b, "id,title,linkType,petId,noticeText,sortOrder");
                  result.put(
                      "imageUrl", store.get("file", text(b, "imageFileId")).get("publicUrl"));
                  return result;
                })
            .toList();
    return map(
        "banners",
        banners,
        "categories",
        categories(),
        "recommendedPets",
        pets.stream()
            .filter(p -> Boolean.TRUE.equals(p.get("isRecommended")))
            .sorted(comprehensive())
            .limit(8)
            .map(this::card)
            .toList(),
        "latestPets",
        pets.stream()
            .sorted(
                Comparator.comparing(
                    p -> Objects.toString(p.get("publishedAt"), ""), Comparator.reverseOrder()))
            .limit(8)
            .map(this::card)
            .toList(),
        "shop",
        publicShop());
  }

  /**
   * 宠物列表：买家搜索与后台列表共用，靠 {@code admin} 分叉两套契约。
   *
   * <p><b>白名单不同</b>：买家可筛品类/品种/价格区间/性别/月龄/毛色/排序；后台只能按
   * 关键词+品类+状态查（后台不需要价格区间，它要的是"把这只找出来改"）。
   * 白名单由 {@code Input} 强制，多传一个字段就是 400——这是防止客户端伪造内部筛选条件。
   *
   * <p><b>状态白名单也不同</b>：买家只允许 {@code on_sale}/{@code reserved}，
   * 后台四态全开。买家传 {@code sold} 会得到 400 而不是空列表——明确拒绝比静默返回空更好排查。
   *
   * <p><b>注意 {@code containsKey} 与 {@code get() != null} 的区别</b>：品类/性别用的是前者，
   * 所以显式传 {@code "category": null} 会被判成 400；而状态/价格用的是后者，null 视为未传。
   * 这是历史行为，前端约定是"不筛就不要带这个键"。
   *
   * <p>真正的分页、排序、区间过滤在 {@link com.warmpaw.repository.PetQueries#page} 里由 SQL 完成；
   * 这里只负责校验参数、并在返回前把每一行投影成 {@link #card}（买家）或 {@link #adminPet}（后台）。
   * 也就是说：<b>SQL 返回的是实体，出口一定是视图</b>，中间不存在"原样透传"的路径。
   *
   * <p>{@code sort} 对后台无意义（后台固定按创建时间倒序），所以后台白名单里没有它；
   * 下面的排序校验对后台永远走默认值，仅为代码统一。
   */
  public Map<String, Object> pets(Map<String, Object> q, boolean admin) {
    new Input(
        q,
        admin
            ? "keyword,category,status,page,pageSize"
            : "keyword,category,breed,minPriceAmount,maxPriceAmount,gender,minAgeMonths,maxAgeMonths,color,status,sort,page,pageSize");
    String status = text(q, "status");
    if (status != null)
      require(
          (admin ? List.of("off", "on_sale", "reserved", "sold") : List.of("on_sale", "reserved"))
              .contains(status),
          400,
          "VALIDATION_ERROR",
          "状态不正确");
    if (q.containsKey("category"))
      require(
          List.of("cat", "dog").contains(text(q, "category")), 400, "VALIDATION_ERROR", "分类不正确");
    if (q.containsKey("gender"))
      require(
          List.of("male", "female").contains(text(q, "gender")), 400, "VALIDATION_ERROR", "性别不正确");
    for (String[] pair :
        List.of(
            new String[] {"minPriceAmount", "maxPriceAmount"},
            new String[] {"minAgeMonths", "maxAgeMonths"})) {
      long min = queryLong(q, pair[0], 0, 0, pair[0].contains("Age") ? 360 : 100000000),
          max =
              queryLong(
                  q,
                  pair[1],
                  pair[0].contains("Age") ? 360 : 100000000,
                  0,
                  pair[0].contains("Age") ? 360 : 100000000);
      require(min <= max, 400, "VALIDATION_ERROR", "最小值不能大于最大值");
    }
    String sort = Objects.toString(q.get("sort"), "comprehensive");
    require(
        List.of("comprehensive", "price_asc", "price_desc", "newest").contains(sort),
        400,
        "VALIDATION_ERROR",
        "排序方式不正确");
    Map<String, Object> result = petQueries.page(q, admin);
    result.put(
        "items",
        objects(result, "items").stream().map(admin ? this::adminPet : this::card).toList());
    return result;
  }

  /**
   * 从查询参数里取一个 long，缺省时返回 {@code fallback}，超范围或非整数一律 400。
   *
   * <p>注意"缺省"只看 {@code containsKey}：显式传 null 会在 {@code toString()} 上抛 NPE，
   * 而不是退回默认值。调用方（尤其是负责拼 URL 的前端）应省略键而不是传 null。
   *
   * <p>判定顺序是先解析再校验范围：{@code "abc"} 得到"必须为整数"，
   * {@code "99999"} 得到"超出范围"，两类错误信息对排查是有用的区分。
   */
  public static long queryLong(
      Map<String, Object> q, String key, long fallback, long min, long max) {
    if (!q.containsKey(key)) return fallback;
    try {
      long n = Long.parseLong(q.get(key).toString());
      require(n >= min && n <= max, 400, "VALIDATION_ERROR", key + "超出范围");
      return n;
    } catch (NumberFormatException e) {
      throw new ApiException(400, "VALIDATION_ERROR", key + "必须为整数");
    }
  }

  /**
   * 内存分页契约：{@code page} 从 1 起、默认 20 条、单页上限 100 条。
   *
   * <p>{@code total} 是<b>切片前</b>的总数，前端据此算页数；{@code page} 越界时返回空数组而不是 400，
   * 这样"删到只剩两页、用户还停在第 3 页"不会变成报错。
   *
   * <p>只适用于已经在内存里的 {@code List}（首页、筛选后的结果）；数据库列表走
   * {@link com.warmpaw.repository.PetQueries#page} 的 LIMIT/OFFSET，不要拿这个套大数据集。
   *
   * <p>单页上限 100 是防内存放大的硬闸：{@code pageSize=1000000} 等于让服务端把全表序列化一遍。
   */
  public static Map<String, Object> page(List<?> list, Map<String, Object> q) {
    long page = queryLong(q, "page", 1, 1, Integer.MAX_VALUE),
        size = queryLong(q, "pageSize", 20, 1, 100);
    return map(
        "items",
        list.stream().skip((page - 1) * size).limit(size).toList(),
        "page",
        page,
        "pageSize",
        size,
        "total",
        list.size());
  }

  /**
   * 取某类协议当前生效的版本（下单前展示、签约时留证）。
   *
   * <p>类型只有 {@code live_pet_trade}（活体交易）与 {@code pickup_confirmation}（到店确认）两种，
   * 其它值 400——协议正文属于法律文本，不接受前端自定义类型去命中别的行。
   *
   * <p>"当前版本"= 已发布集合里的<b>第一条</b>，而 {@code store.list} 按
   * {@code created_at DESC, id DESC} 排序，所以实际语义是"最近创建的已发布版本"。
   * 发布新版本时依赖这个创建顺序，而不是另设 {@code isCurrent} 标记。
   *
   * <p>一条都没有时抛 <b>503</b> 而不是 404 或空对象：这不是"资源不存在"，而是
   * "门店还没配好、暂时无法营业"，前端应提示稍后再试而不是当成页面丢失。
   */
  public Map<String, Object> currentAgreement(String type) {
    require(
        List.of("live_pet_trade", "pickup_confirmation").contains(type),
        400,
        "VALIDATION_ERROR",
        "协议类型不正确");
    return store.list("agreement").stream()
        .filter(a -> type.equals(text(a, "type")) && "published".equals(text(a, "status")))
        .findFirst()
        .orElseThrow(() -> new ApiException(503, "AGREEMENT_NOT_READY", "尚未发布协议"));
  }

}
