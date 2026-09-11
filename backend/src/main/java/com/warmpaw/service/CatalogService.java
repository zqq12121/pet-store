package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/** 商品、门店和协议；公开投影始终移除私有证明、归属和经营内部字段。 */
@Service
public class CatalogService {
  private final BusinessRepository store;
  private final com.warmpaw.repository.PetQueries petQueries;

  public CatalogService(BusinessRepository store, com.warmpaw.repository.PetQueries petQueries) {
    this.store = store;
    this.petQueries = petQueries;
  }

  public Map<String, Object> shop() {
    return store.get("shop", "shop_1");
  }

  public Map<String, Object> publicShop() {
    Map<String, Object> s = shop();
    return select(
        s,
        "id,name,address,latitude,longitude,coordinateSystem,phone,wechat,businessHours,pickupInstructions");
  }

  public static Map<String, Object> select(Map<String, Object> source, String fields) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String f : fields.split(",")) result.put(f, source.get(f));
    return result;
  }

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

  public static LocalDate parseDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (Exception e) {
      throw new ApiException(400, "VALIDATION_ERROR", "日期格式不正确");
    }
  }

  public boolean validQuarantine(Map<String, Object> pet) {
    Map<String, Object> q = object(pet, "quarantine");
    return !q.isEmpty()
        && !strings(q, "publicImageFileIds").isEmpty()
        && (q.get("validUntil") == null
            || !parseDate(text(q, "validUntil"))
                .isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
  }

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

  public boolean purchasable(Map<String, Object> pet) {
    return "on_sale".equals(text(pet, "status"))
        && validQuarantine(pet)
        && number(pet, "priceAmount") > 0
        && store.occupation(text(pet, "id")) == null;
  }

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

  public Map<String, Object> publicPet(String id) {
    Map<String, Object> p = store.get("pet", id);
    require(!"off".equals(text(p, "status")), 404, "RESOURCE_NOT_FOUND", "宠物不存在或已下架");
    return detail(p);
  }

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

  private Comparator<Map<String, Object>> comprehensive() {
    return Comparator.<Map<String, Object>, Boolean>comparing(
            p -> !Boolean.TRUE.equals(p.get("isRecommended")))
        .thenComparingLong(p -> number(p, "recommendationOrder"))
        .thenComparing(p -> Objects.toString(p.get("publishedAt"), ""), Comparator.reverseOrder())
        .thenComparing(p -> text(p, "id"), Comparator.reverseOrder());
  }

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
                  .anyMatch(k -> !canonical(proposed.get(k)).equals(canonical(old.get(k))))
              || !validQuarantine(old);
      old.putAll(p);
      p = old;
      if (healthChanged) p.put("status", "off");
      store.save(p);
    }
    store.audit(admin, "pet.write", text(p, "id"));
    return adminPet(p);
  }

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

  public Map<String, Object> updateShop(Map<String, Object> body, String admin) {
    Input in =
        new Input(
            body,
            "name,address,latitude,longitude,coordinateSystem,phone,wechat,businessHours,pickupInstructions,version,banners,paymentTimeoutMinutes,pickupRetentionHours,exchangeEnabled");
    Map<String, Object> s = shop();
    BusinessRepository.version(s, in.integer("version", 1, Integer.MAX_VALUE));
    for (String key :
        List.of("name", "address", "phone", "wechat", "businessHours", "pickupInstructions"))
      s.put(
          key,
          in.str(key, 1, key.equals("pickupInstructions") ? 1000 : key.equals("name") ? 80 : 200));
    s.put("latitude", in.decimal("latitude", -90, 90));
    s.put("longitude", in.decimal("longitude", -180, 180));
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
