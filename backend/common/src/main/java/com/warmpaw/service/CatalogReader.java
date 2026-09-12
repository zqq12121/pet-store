package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 共享目录读取与快照投影；订单事务使用同库读取，不包含商品、门店或协议写入。 */
public class CatalogReader {
  protected final BusinessRepository store;
  private final com.warmpaw.repository.PetQueries petQueries;

  public CatalogReader(BusinessRepository store, com.warmpaw.repository.PetQueries petQueries) {
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
