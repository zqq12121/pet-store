package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/** 管理服务独占目录写入：商品、门店与协议变更。 */
@Service
public class CatalogService extends CatalogReader {
  public CatalogService(BusinessRepository store, com.warmpaw.repository.PetQueries petQueries) {
    super(store, petQueries);
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
    com.warmpaw.common.AppointmentHours.parse(text(s, "businessHours"));
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
