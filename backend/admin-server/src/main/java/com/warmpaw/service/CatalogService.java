package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 目录写服务：宠物档案、协议版本、门店配置的变更入口。
 *
 * <p><b>为什么继承 {@link CatalogReader}</b>：读能力（宠物卡片/详情投影、门店公开信息、协议查询）
 * 是 admin-server 与 order-server 共用的，放在 common 模块；写能力只有后台需要，
 * 所以放在这里继承读实现。<b>任何写方法都不能下沉到 CatalogReader</b>——那会让交易服务
 * 也具备改价、上下架的能力，共享库的边界就破了。
 *
 * <p><b>本类最重要的一条业务规则</b>：修改宠物的健康相关信息会自动下架（见 {@link #writePet}）。
 * 因为检疫、疫苗、体重、月龄这些字段是买家决策依据，改动后必须重新经过店长的"上架审核"，
 * 不能静默生效。
 *
 * <p>所有方法都要求调用方已在事务中持锁，且 {@code admin} 参数是操作人 id（用于审计）。
 */
@Service
public class CatalogService extends CatalogReader {
  public CatalogService(BusinessRepository store, com.warmpaw.repository.PetQueries petQueries) {
    super(store, petQueries);
  }

  /**
   * 新建或更新宠物档案（{@code id == null} 为新建）。
   *
   * <p><b>字段校验</b>：字符串按语义给不同上限（名称 80、品种 50、颜色 30、疫苗/驱虫说明 500、
   * 长文本 10000）；体重强制最多两位小数（价格与后续按克计价都依赖这个精度）；
   * 出生日期不能晚于今天。
   *
   * <p><b>图片与视频先校验归属</b>：{@code asset(file, purpose, admin, null)} 会确认文件属于
   * 当前操作人且用途匹配，防止把别人的图片挂到自己的宠物上。
   *
   * <p><b>更新时的乐观锁</b>：必须带 {@code version}，且状态只允许 {@code off}/{@code on_sale}
   * ——已售出（{@code sold}）或被订单占用（{@code reserved}）的宠物不允许编辑，
   * 否则会改掉买家已下单商品的历史信息（订单虽存快照，但运营侧会混乱）。
   *
   * <p><b>健康信息变更 ⇒ 自动下架</b>：比较新旧值，只要检疫、疫苗、驱虫、健康描述、
   * 品种、性别、生日、月龄、体重、颜色、名称中任意一项发生变化，或原检疫证明已失效，
   * 就把状态打回 {@code off}，等待重新审核上架。这是防止"改完就直接卖"的关键闸门。
   *
   * <p>新建的宠物一律从 {@code off} 开始，同样需要走一次上架审核。
   */
  public Map<String, Object> writePet(String id, Map<String, Object> body, String admin) {
    Input in =
        new Input(
            body,
            "name,category,breed,priceAmount,gender,ageMonths,birthDate,weightKg,color,personalityTags,vaccineStatus,dewormStatus,description,feedingNotes,healthDescription,imageFileIds,videoFileId,quarantine,isRecommended,recommendationOrder"
                + (id == null ? "" : ",version"));
    Map<String, Object> old = id == null ? null : store.get("pet", id);
    if (old != null) {
      BusinessRepository.version(old, in.integer("version", 1, Integer.MAX_VALUE));
      BusinessRepository.state(old, "off", "on_sale");
    }
    Map<String, Object> p = new LinkedHashMap<>();
    for (String key :
        List.of(
            "name",
            "breed",
            "color",
            "vaccineStatus",
            "dewormStatus",
            "description",
            "feedingNotes",
            "healthDescription"))
      p.put(
          key,
          in.str(
              key,
              1,
              switch (key) {
                case "name" -> 80;
                case "breed" -> 50;
                case "color" -> 30;
                case "vaccineStatus", "dewormStatus" -> 500;
                default -> 10000;
              }));
    p.put("category", in.choice("category", "cat,dog"));
    p.put("gender", in.choice("gender", "male,female"));
    p.put("priceAmount", in.integer("priceAmount", 1, 100000000));
    p.put("ageMonths", in.integer("ageMonths", 0, 360));
    double weight = in.decimal("weightKg", 0.01, 200);
    // 用 ×100 后取整的差判断，避免浮点误差导致 3.10 被判为超过两位小数。
    require(
        Math.abs(weight * 100 - Math.rint(weight * 100)) < 0.00001,
        400,
        "VALIDATION_ERROR",
        "体重最多两位小数");
    p.put("weightKg", weight);
    p.put("birthDate", in.optional("birthDate", 10));
    if (p.get("birthDate") != null)
      require(
          !parseDate(text(p, "birthDate")).isAfter(LocalDate.now()),
          400,
          "VALIDATION_ERROR",
          "出生日期不能晚于今天");
    List<String> tags = in.has("personalityTags") ? in.ids("personalityTags", 0, 10) : List.of();
    require(tags.stream().allMatch(t -> t.length() <= 20), 400, "VALIDATION_ERROR", "性格标签过长");
    p.put("personalityTags", tags);
    List<String> images = in.ids("imageFileIds", 1, 9);
    for (String file : images) asset(file, "pet_image", admin, null);
    p.put("imageFileIds", images);
    String video = in.optional("videoFileId", 64);
    if (video != null) asset(video, "pet_video", admin, null);
    p.put("videoFileId", video);
    p.put(
        "quarantine",
        body.get("quarantine") == null ? null : quarantine(object(body, "quarantine"), admin));
    p.put("isRecommended", in.has("isRecommended") ? in.bool("isRecommended") : false);
    p.put(
        "recommendationOrder",
        in.has("recommendationOrder")
            ? in.integer("recommendationOrder", 0, Integer.MAX_VALUE)
            : 0);
    if (old == null) {
      p.putAll(
          map(
              "status",
              "off",
              "activeOrderId",
              null,
              "reviewedAt",
              null,
              "lastSaleReviewNote",
              null,
              "publishedAt",
              null));
      store.create("pet", admin, null, p);
    } else {
      Map<String, Object> proposed = p;
      // 健康相关字段任一变化都要重新审核：这些是买家决策依据，不能被静默修改。
      boolean healthChanged =
          List.of(
                      "quarantine",
                      "vaccineStatus",
                      "dewormStatus",
                      "healthDescription",
                      "name",
                      "category",
                      "breed",
                      "gender",
                      "birthDate",
                      "ageMonths",
                      "weightKg",
                      "color")
                  .stream()
                  .anyMatch(k -> !sameValue(proposed.get(k), old.get(k)))
              || !validQuarantine(old);
      old.putAll(p);
      p = old;
      if (healthChanged) p.put("status", "off");
      store.save(p);
    }
    store.audit(admin, "pet.write", text(p, "id"));
    return adminPet(p);
  }

  /**
   * 健康字段是否未变。数字按数值比较：库里 DECIMAL 读出来是 {@code 9.00}，请求 JSON 解析成
   * {@code 9.0}，按序列化文本比会误判为"体重变了"，导致后台随便改个价格就把在售宠物下架。
   */
  private static boolean sameValue(Object proposed, Object old) {
    if (proposed instanceof Number a && old instanceof Number b)
      return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
    return canonical(proposed).equals(canonical(old));
  }

  /**
   * 上架 / 下架。
   *
   * <p><b>上架要过五道检查</b>，缺一不可：
   *
   * <ol>
   *   <li>必须显式勾选 {@code healthyForSale} 与 {@code quarantineVerified} 两个承诺；
   *   <li>检疫证明必须有效（{@code validQuarantine}），否则 422；
   *   <li>不能被任何订单占用（{@code occupation == null}）；
   *   <li>若这只宠物有过"退回记录"的售后，该售后必须已 {@code resolved}/{@code refunded}——
   *       避免把正在纠纷中的宠物重新卖掉；
   *   <li>必须填写审核备注 {@code reviewNote}，留下"谁基于什么判断认为可以卖"的记录。
   * </ol>
   *
   * <p>上架时记录 {@code reviewedAt}/{@code publishedAt}，下架只需原因。
   * 状态前置条件是"上架只能从 off，下架只能从 on_sale"，用 {@code state(...)} 保证。
   */
  public Map<String, Object> publish(
      String id, Map<String, Object> body, String admin, boolean publish) {
    Map<String, Object> p = store.get("pet", id);
    Input in =
        new Input(
            body,
            publish ? "version,healthyForSale,quarantineVerified,reviewNote" : "version,reason");
    BusinessRepository.version(p, in.integer("version", 1, Integer.MAX_VALUE));
    BusinessRepository.state(p, publish ? "off" : "on_sale");
    if (publish) {
      in.yes("healthyForSale");
      in.yes("quarantineVerified");
      require(validQuarantine(p), 422, "QUARANTINE_REQUIRED", "请补充有效检疫证明");
      require(store.occupation(id) == null, 409, "PET_NOT_AVAILABLE", "宠物仍被订单占用");
      for (Map<String, Object> sale : store.list("after_sale"))
        if (id.equals(text(sale, "petId")) && sale.get("returnRecord") != null)
          require(
              List.of("resolved", "refunded").contains(text(sale, "status")),
              409,
              "REFUND_IN_PROGRESS",
              "原售后尚未完成");
      p.put("reviewedAt", Instant.now().toString());
      p.put("lastSaleReviewNote", in.str("reviewNote", 1, 1000));
      p.put("publishedAt", Instant.now().toString());
    } else in.str("reason", 1, 500);
    p.put("status", publish ? "on_sale" : "off");
    store.save(p);
    store.audit(admin, publish ? "pet.publish" : "pet.unpublish", id);
    return adminPet(p);
  }

  /**
   * 新建协议版本（草稿）。
   *
   * <p>两种协议：{@code live_pet_trade}（活体交易主协议，可设健康保障天数 1~30）与
   * {@code pickup_confirmation}（现场确认文本，不设保障天数）。后者传保障天数直接 400，
   * 因为它只是一份确认清单，不承担时限承诺。
   *
   * <p><b>幂等键是 {@code type:version}</b>，已存在则 409。协议是法律文本，
   * 同一个版本号不允许被覆盖——否则已签署订单引用的内容会变。
   *
   * <p>{@code contentHash} 在创建时算好并固化。之后订单、确认记录都引用这个哈希，
   * 只要文本被改过一个字，哈希就对不上，能立刻发现。
   *
   * <p>新协议状态为 {@code draft}，必须显式发布才生效。
   */
  public Map<String, Object> createAgreement(Map<String, Object> body, String admin) {
    Input in = new Input(body, "type,version,title,content,healthGuaranteeDays");
    String type = in.choice("type", "live_pet_trade,pickup_confirmation"),
        version = in.str("version", 1, 32),
        content = in.str("content", 1, 50000);
    require(
        store.byKey("agreement", type + ":" + version) == null, 409, "VERSION_CONFLICT", "协议版本已存在");
    require(
        type.equals("live_pet_trade") || !in.has("healthGuaranteeDays"),
        400,
        "VALIDATION_ERROR",
        "现场确认协议不设置保障天数");
    return store.create(
        "agreement",
        admin,
        type + ":" + version,
        map(
            "type",
            type,
            "version",
            version,
            "title",
            in.str("title", 1, 100),
            "content",
            content,
            "contentFormat",
            "markdown",
            "contentHash",
            hash(content),
            "healthGuaranteeDays",
            type.equals("live_pet_trade") ? in.integer("healthGuaranteeDays", 1, 30) : null,
            "status",
            "draft",
            "publishedAt",
            null));
  }

  /**
   * 发布协议：同类型只保留一个 {@code published}。
   *
   * <p>发布新版本时，<b>同类型的旧版本自动转为 {@code retired}</b>——不是删除。
   * 历史订单和确认记录仍引用旧版本内容与哈希，记录必须留着才能自证"当时签的是什么"。
   *
   * <p>三条约束：
   *
   * <ul>
   *   <li>{@code retired} 的协议不能重新发布（历史版本一旦退役就永久固化）；
   *   <li>已发布状态下重复调用直接返回原对象（幂等，不报错）；
   *   <li>必须勾选 {@code reviewConfirmed}，确认已人工复核文本内容。
   * </ul>
   */
  public Map<String, Object> publishAgreement(String id, Map<String, Object> body, String admin) {
    new Input(body, "reviewConfirmed").yes("reviewConfirmed");
    Map<String, Object> a = store.get("agreement", id);
    require(!"retired".equals(text(a, "status")), 409, "AGREEMENT_STATE_CONFLICT", "历史协议不能重新发布");
    if ("published".equals(text(a, "status"))) return a;
    for (Map<String, Object> old : store.list("agreement"))
      if (Objects.equals(old.get("type"), a.get("type"))
          && "published".equals(text(old, "status"))) {
        old.put("status", "retired");
        store.save(old);
      }
    a.put("status", "published");
    a.put("publishedAt", Instant.now().toString());
    store.save(a);
    store.audit(admin, "agreement.publish", id);
    return a;
  }

  /**
   * 更新门店配置（单店，全量覆盖式更新）。
   *
   * <p><b>营业时间</b>：写入前用 {@code AppointmentHours.parse} 试解析一遍，
   * 格式不合法立刻 400。这很重要——营业时间直接决定预约能否创建，
   * 存进一个解析不了的值会让整个预约功能失效。
   *
   * <p><b>经纬度必须成对</b>：只填纬度或只填经度都无法定位，直接拒绝。
   * 坐标系统固定 {@code gcj02}（高德/腾讯地图标准），跟随中国法规。
   *
   * <p><b>联系方式可以为空</b>：不编造电话或微信号填充必填项，缺省存空串。
   *
   * <p><b>轮播图整体替换</b>：每次提交全量 {@code banners}（最多 10 张）。逐张校验：
   * 图片归属、跳转类型（宠物/公告/无）、跳转目标的附加字段必须与类型匹配
   * （{@code none} 不能带 {@code petId}/{@code noticeText}）；
   * 已有 id 必须属于当前门店，防止改到别家店的轮播。
   *
   * <p>{@code paymentTimeoutMinutes}（5~120 分钟）与 {@code pickupRetentionHours}（1~168 小时）
   * 是交易时限配置，影响订单过期与自提保留，属于敏感配置。
   */
  public Map<String, Object> updateShop(Map<String, Object> body, String admin) {
    Input in =
        new Input(
            body,
            "name,address,latitude,longitude,coordinateSystem,phone,wechat,businessHours,pickupInstructions,version,banners,paymentTimeoutMinutes,pickupRetentionHours,exchangeEnabled");
    Map<String, Object> s = shop();
    BusinessRepository.version(s, in.integer("version", 1, Integer.MAX_VALUE));
    for (String key :
        List.of("name", "address", "businessHours", "pickupInstructions"))
      s.put(
          key,
          in.str(key, 1, key.equals("pickupInstructions") ? 1000 : key.equals("name") ? 80 : 200));
    com.warmpaw.common.AppointmentHours.parse(text(s, "businessHours"));
    // 未提供联系方式时保存为空，不用虚构电话或微信填充必填项。
    for (String key : List.of("phone", "wechat")) s.put(key, Objects.requireNonNullElse(in.optional(key, 200), ""));
    // 地图位置可以不填写；填写时经纬度必须成对且在有效范围内。
    require(
        (body.get("latitude") == null) == (body.get("longitude") == null),
        400,
        "VALIDATION_ERROR",
        "经纬度必须同时填写或同时留空");
    s.put("latitude", body.get("latitude") == null ? null : in.decimal("latitude", -90, 90));
    s.put("longitude", body.get("longitude") == null ? null : in.decimal("longitude", -180, 180));
    s.put("coordinateSystem", in.choice("coordinateSystem", "gcj02"));
    s.put("paymentTimeoutMinutes", in.integer("paymentTimeoutMinutes", 5, 120));
    s.put("pickupRetentionHours", in.integer("pickupRetentionHours", 1, 168));
    s.put("exchangeEnabled", in.bool("exchangeEnabled"));
    require(
        body.get("banners") instanceof List<?> && objects(body, "banners").size() <= 10,
        400,
        "VALIDATION_ERROR",
        "轮播图最多10张");
    List<Map<String, Object>> banners = new ArrayList<>();
    for (Map<String, Object> b : objects(body, "banners")) {
      Input bi = new Input(b, "id,title,imageFileId,linkType,petId,noticeText,sortOrder,enabled");
      bi.str("title", 1, 80);
      asset(bi.str("imageFileId", 1, 64), "banner", admin, null);
      String link = bi.choice("linkType", "pet,notice,none");
      if (link.equals("pet")) store.get("pet", bi.str("petId", 1, 64));
      if (link.equals("notice")) bi.str("noticeText", 1, 2000);
      if (link.equals("none"))
        require(
            b.get("petId") == null && b.get("noticeText") == null,
            400,
            "VALIDATION_ERROR",
            "无链接轮播不能附带跳转");
      bi.integer("sortOrder", 0, Integer.MAX_VALUE);
      bi.bool("enabled");
      Map<String, Object> item = copy(b);
      if (item.get("id") == null) item.put("id", id("banner"));
      else
        require(
            objects(s, "banners").stream().anyMatch(old -> old.get("id").equals(item.get("id"))),
            400,
            "VALIDATION_ERROR",
            "轮播ID不属于当前门店");
      banners.add(item);
    }
    s.put("banners", banners);
    store.save(s);
    store.audit(admin, "shop.update", "shop_1");
    return s;
  }
}
