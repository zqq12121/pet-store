package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 订单是金额、协议和库存的事实来源。调用方在数据库事务中持有单店业务锁。 */
@Service
public class OrderService {
  private final BusinessRepository store;
  private final CatalogReader catalog;
  private final AuthService auth;

  public OrderService(BusinessRepository store, CatalogReader catalog, AuthService auth) {
    this.store = store;
    this.catalog = catalog;
    this.auth = auth;
  }

  public Map<String, Object> owned(String id, AuthService.Actor actor) {
    return store.ownedOrder(id, actor);
  }

  public Map<String, Object> preview(Map<String, Object> body) {
    Input in = new Input(body, "petId");
    Map<String, Object> p = store.get("pet", in.str("petId", 1, 64)),
        a = catalog.currentAgreement("live_pet_trade"),
        s = catalog.shop();
    require(!"off".equals(text(p, "status")), 404, "RESOURCE_NOT_FOUND", "宠物不可访问");
    return map(
        "product",
        catalog.card(p),
        "productVersion",
        p.get("version"),
        "amount",
        p.get("priceAmount"),
        "currency",
        "CNY",
        "shop",
        catalog.publicShop(),
        "agreementVersion",
        a.get("version"),
        "agreementContentHash",
        a.get("contentHash"),
        "paymentTimeoutMinutes",
        s.get("paymentTimeoutMinutes"),
        "pickupRetentionHours",
        s.get("pickupRetentionHours"),
        "healthGuaranteeDays",
        a.get("healthGuaranteeDays"),
        "purchaseAllowed",
        catalog.purchasable(p),
        "blockedReason",
        catalog.purchasable(p) ? null : "当前宠物不可购买");
  }

  public Map<String, Object> create(Map<String, Object> body, AuthService.Actor actor) {
    Input in =
        new Input(
            body,
            "petId,productVersion,expectedAmount,contactName,contactPhone,remark,agreementVersion,agreementContentHash,agreementAccepted");
    in.yes("agreementAccepted");
    Map<String, Object> p = store.get("pet", in.str("petId", 1, 64));
    require(catalog.purchasable(p), 409, "PET_NOT_AVAILABLE", "宠物已被预订、下架或证明无效");
    require(
        number(p, "priceAmount") == in.integer("expectedAmount", 1, 100000000),
        409,
        "PRICE_CHANGED",
        "价格已更新，请重新结算");
    BusinessRepository.version(p, in.integer("productVersion", 1, Integer.MAX_VALUE));
    Map<String, Object> a = catalog.currentAgreement("live_pet_trade");
    require(
        Objects.equals(a.get("version"), in.str("agreementVersion", 1, 32))
            && Objects.equals(a.get("contentHash"), in.str("agreementContentHash", 64, 64)),
        409,
        "AGREEMENT_CHANGED",
        "协议已更新，请重新阅读");
    String phone = in.phone("contactPhone");
    require(phone.equals(actor.phone()), 400, "VALIDATION_ERROR", "联系人必须为登录手机号本人");
    Instant now = Instant.now();
    Map<String, Object> s = catalog.shop();
    Map<String, Object> o =
        map(
            "orderNo",
            "WP" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
            "petId",
            p.get("id"),
            "status",
            "pending_paid",
            "amount",
            p.get("priceAmount"),
            "refundedAmount",
            0,
            "currency",
            "CNY",
            "contactName",
            in.str("contactName", 1, 30),
            "contactPhone",
            phone,
            "remark",
            Objects.toString(in.optional("remark", 500), ""),
            "expiresAt",
            now.plusSeconds(number(s, "paymentTimeoutMinutes") * 60).toString(),
            "pickupRetentionHours",
            s.get("pickupRetentionHours"),
            "healthGuaranteeDays",
            a.get("healthGuaranteeDays"),
            "product",
            CatalogReader.select(catalog.card(p), "id,name,breed,coverUrl"),
            "priceSnapshot",
            map(
                "unitPriceAmount",
                p.get("priceAmount"),
                "quantity",
                1,
                "totalAmount",
                p.get("priceAmount"),
                "currency",
                "CNY"),
            "productSnapshot",
            CatalogReader.select(
                catalog.detail(p),
                "name,category,breed,gender,ageMonths,weightKg,color,vaccineStatus,dewormStatus,images,quarantine"),
            "shopSnapshot",
            catalog.publicShop(),
            "agreement",
            map(
                "version",
                a.get("version"),
                "contentHash",
                a.get("contentHash"),
                "title",
                a.get("title"),
                "content",
                a.get("content"),
                "signedAt",
                now.toString()),
            "payment",
            map("paymentId", null, "status", "not_created", "paidAmount", 0, "paidAt", null),
            "pickup",
            null,
            "pickupEvidence",
            null,
            "deliveryQuarantine",
            null,
            "paidAt",
            null,
            "pickupDeadlineAt",
            null,
            "completedAt",
            null,
            "healthGuaranteeExpiresAt",
            null,
            "pickedUpAt",
            null,
            "closedAt",
            null,
            "cancelledAt",
            null,
            "cancelReason",
            null);
    store.create("order", actor.id(), text(o, "orderNo"), o);
    store.occupy(text(p, "id"), text(o, "id"));
    p.put("status", "reserved");
    p.put("activeOrderId", o.get("id"));
    store.save(p);
    store.audit(actor.id(), "order.create", text(o, "id"));
    return o;
  }

  public List<Map<String, Object>> sales(Map<String, Object> order) {
    return store.list("after_sale").stream()
        .filter(s -> Objects.equals(s.get("orderId"), order.get("id")))
        .toList();
  }

  public boolean blocked(Map<String, Object> o) {
    return sales(o).stream()
        .anyMatch(
            s ->
                !List.of("rejected", "resolved", "refunded", "cancelled")
                    .contains(text(s, "status")));
  }

  public void pickupAllowed(Map<String, Object> o) {
    BusinessRepository.state(o, "paid");
    require(
        !Instant.now().isAfter(Instant.parse(text(o, "pickupDeadlineAt"))),
        409,
        "ORDER_EXPIRED",
        "自提已超期");
    require(!blocked(o), 409, "ORDER_STATE_CONFLICT", "售后处理中不能交付");
  }

  public Map<String, Object> eligibility(Map<String, Object> o) {
    List<String> types = new ArrayList<>(), resolutions = new ArrayList<>();
    if (!o.containsKey("appointment") && !blocked(o) && number(o, "refundedAmount") < number(o, "amount")) {
      if ("paid".equals(text(o, "status"))) {
        types.add("refund_before_pickup");
        resolutions.add("full_refund");
      }
      if ("completed".equals(text(o, "status"))
          && o.get("healthGuaranteeExpiresAt") != null
          && !Instant.now().isAfter(Instant.parse(text(o, "healthGuaranteeExpiresAt")))) {
        types.add("health_issue");
        resolutions.addAll(List.of("full_refund", "treatment_share"));
        if (Boolean.TRUE.equals(catalog.shop().get("exchangeEnabled"))
            && number(o, "refundedAmount") == 0) resolutions.add("exchange");
      }
    }
    return map(
        "allowedTypes",
        types,
        "allowedResolutions",
        resolutions,
        "blockedReason",
        types.isEmpty() ? "当前不能在线申请，请联系店主" : null);
  }

  public Map<String, Object> summary(Map<String, Object> o) {
    Map<String, Object> r =
        CatalogReader.select(
            o,
            "id,orderNo,status,product,amount,refundedAmount,currency,createdAt,expiresAt,paidAt,pickupDeadlineAt,completedAt,appointment");
    r.put(
        "statusText",
        switch (text(o, "status")) {
          case "pending_confirmation" -> "预约待确认";
          case "reservation_confirmed" -> "已确认 · 待到店";
          case "expired" -> "预约已过期";
          case "pending_paid" -> "历史待付款";
          case "closing" -> "关单处理中";
          case "cancelled" -> "已取消";
          case "paid" -> "待自提";
          case "completed" -> "已完成";
          case "refunding" -> "退款中";
          case "refunded" -> "已退款";
          default -> "处理中";
        });
    List<Map<String, Object>> sales = sales(o);
    r.put("afterSaleStatus", sales.isEmpty() ? "none" : sales.getFirst().get("status"));
    List<String> actions = new ArrayList<>(List.of("contact_shop"));
    if (List.of("pending_paid", "pending_confirmation", "reservation_confirmed").contains(text(o, "status"))) actions.add("cancel");
    if ("paid".equals(text(o, "status"))
        && !blocked(o)
        && !Instant.now().isAfter(Instant.parse(text(o, "pickupDeadlineAt")))) {
      actions.add("view_pickup");
      actions.add("confirm_health");
    }
    if (!strings(eligibility(o), "allowedTypes").isEmpty()) actions.add("apply_after_sale");
    if (!sales.isEmpty()) actions.add("view_after_sale");
    r.put("availableActions", actions);
    return r;
  }

  public Map<String, Object> detail(Map<String, Object> o, boolean admin) {
    Map<String, Object> r = summary(o);
    r.putAll(
        CatalogReader.select(
            o,
            "priceSnapshot,productSnapshot,shopSnapshot,contactName,remark,agreement,payment,pickupEvidence,healthGuaranteeExpiresAt,deliveryQuarantine,closedAt,pickedUpAt,cancelledAt,cancelReason"));
    r.put(
        "pickupEvidence",
        o.get("pickupEvidence") == null
            ? null
            : CatalogReader.select(
                object(o, "pickupEvidence"),
                "confirmationId,textVersion,textHash,confirmationText,checks,confirmedAt,method,operatorId,deliveredAt"));
    r.put("contactPhoneMasked", AuthService.mask(text(o, "contactPhone")));
    r.put("pickup", "paid".equals(text(o, "status")) && !blocked(o) ? o.get("pickup") : null);
    List<Map<String, Object>> sales = sales(o);
    r.put(
        "afterSale",
        sales.isEmpty()
            ? null
            : CatalogReader.select(
                sales.getFirst(),
                "id,orderId,type,status,requestedResolution,approvedResolution,createdAt,updatedAt"));
    r.put("afterSaleEligibility", eligibility(o));
    r.put(
        "refunds",
        store.list("refund").stream()
            .filter(f -> Objects.equals(f.get("orderId"), o.get("id")))
            .map(
                f ->
                    CatalogReader.select(
                        f,
                        "id,refundNo,afterSaleId,reason,amount,status,createdAt,succeededAt,failureReason"))
            .toList());
    if (admin) {
      r.put("userId", o.get("ownerId"));
      r.put("contactPhone", o.get("contactPhone"));
    }
    return r;
  }

  public void release(Map<String, Object> o) {
    String pet = text(o, "petId");
    if (store.release(pet, text(o, "id")) > 0) {
      Map<String, Object> p = store.get("pet", pet);
      p.put("activeOrderId", null);
      if (!"sold".equals(text(p, "status")) && !"off".equals(text(p, "status")))
        p.put("status", catalog.validQuarantine(p) ? "on_sale" : "off");
      store.save(p);
    }
  }

  public Map<String, Object> confirm(
      Map<String, Object> o, Map<String, Object> body, AuthService.Actor actor) {
    pickupAllowed(o);
    Map<String, Object> e =
        confirmation(body, actor, text(o, "contactPhone"), "pickup_confirm", text(o, "id"));
    Instant valid = Instant.parse(text(e, "validUntil")),
        deadline = Instant.parse(text(o, "pickupDeadlineAt"));
    if (deadline.isBefore(valid)) e.put("validUntil", deadline.toString());
    e.put(
        "healthGuaranteeExpiresAt",
        Instant.parse(text(e, "confirmedAt"))
            .plusSeconds(number(o, "healthGuaranteeDays") * 86400)
            .toString());
    store.create("confirmation", actor.id(), null, e);
    o.put("pickupEvidence", e);
    o.put("healthGuaranteeExpiresAt", e.get("healthGuaranteeExpiresAt"));
    Map<String, Object> pickup = object(o, "pickup");
    pickup.put("buyerConfirmedAt", e.get("confirmedAt"));
    store.save(o);
    return CatalogReader.select(
        e, "confirmationId,confirmedAt,validUntil,healthGuaranteeExpiresAt");
  }

  public Map<String, Object> confirmation(
      Map<String, Object> body,
      AuthService.Actor actor,
      String phone,
      String purpose,
      String scope) {
    Input in =
        new Input(
            body,
            "smsRequestId,smsCode,confirmationVersion,confirmationContentHash,accepted,checks"
                + (purpose.equals("exchange_confirm") ? ",replacementPetId" : ""));
    in.yes("accepted");
    Map<String, Object> checks = object(body, "checks");
    Input ci = new Input(checks, "mentalState,eyesAndNose,coat,excretion");
    for (String key : List.of("mentalState", "eyesAndNose", "coat", "excretion")) ci.yes(key);
    Map<String, Object> a = catalog.currentAgreement("pickup_confirmation");
    require(
        a.get("version").equals(in.str("confirmationVersion", 1, 32))
            && a.get("contentHash").equals(in.str("confirmationContentHash", 64, 64)),
        409,
        "AGREEMENT_CHANGED",
        "确认文本已更新");
    auth.checkSms(in.str("smsRequestId", 1, 64), in.str("smsCode", 6, 6), phone, purpose, scope);
    Instant now = Instant.now();
    return map(
        "confirmationId",
        id("confirm"),
        "textVersion",
        a.get("version"),
        "textHash",
        a.get("contentHash"),
        "confirmationText",
        a.get("content"),
        "checks",
        copy(checks),
        "confirmedAt",
        now.toString(),
        "validUntil",
        now.plusSeconds(600).toString(),
        "method",
        "sms",
        "operatorId",
        null,
        "deliveredAt",
        null,
        "phoneHash",
        hash(phone),
        "smsRequestId",
        body.get("smsRequestId"),
        "scope",
        scope);
  }

  public static boolean matchesCode(Map<String, Object> pickup, Map<String, Object> body) {
    boolean code = body.get("pickupCode") != null, qr = body.get("qrPayload") != null;
    return code != qr
        && (code
            ? Objects.equals(pickup.get("code"), body.get("pickupCode"))
            : Objects.equals(pickup.get("qrPayload"), body.get("qrPayload")));
  }

  public Map<String, Object> lookup(Map<String, Object> body) {
    new Input(body, "pickupCode,qrPayload");
    Map<String, Object> o =
        store.list("order").stream()
            .filter(x -> "paid".equals(text(x, "status")) && matchesCode(object(x, "pickup"), body))
            .findFirst()
            .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND", "自提凭证无效"));
    Map<String, Object> e = object(o, "pickupEvidence");
    boolean valid = !e.isEmpty() && Instant.parse(text(e, "validUntil")).isAfter(Instant.now());
    boolean allowed =
        valid && !blocked(o) && !Instant.now().isAfter(Instant.parse(text(o, "pickupDeadlineAt")));
    return map(
        "order",
        summary(o),
        "contactName",
        o.get("contactName"),
        "contactPhoneMasked",
        AuthService.mask(text(o, "contactPhone")),
        "buyerConfirmation",
        e.isEmpty()
            ? null
            : map(
                "id",
                e.get("confirmationId"),
                "confirmedAt",
                e.get("confirmedAt"),
                "validUntil",
                e.get("validUntil")),
        "checksPassed",
        valid,
        "pickupAllowed",
        allowed,
        "blockedReason",
        allowed ? null : "需有效的买家确认且订单允许交付");
  }

  public Map<String, Object> deliveryQuarantine(
      Map<String, Object> pet, Map<String, Object> body, String admin) {
    Map<String, Object> q =
        body.get("deliveryQuarantine") == null
            ? object(pet, "quarantine")
            : catalog.quarantine(object(body, "deliveryQuarantine"), admin);
    require(catalog.validQuarantine(map("quarantine", q)), 422, "QUARANTINE_REQUIRED", "交付检疫证明无效");
    return q;
  }

  public Map<String, Object> pickup(
      Map<String, Object> o, Map<String, Object> body, AuthService.Actor actor) {
    Input in =
        new Input(
            body, "confirmationId,pickupCode,qrPayload,quarantineVerified,deliveryQuarantine");
    in.yes("quarantineVerified");
    pickupAllowed(o);
    require(matchesCode(object(o, "pickup"), body), 404, "RESOURCE_NOT_FOUND", "自提凭证无效");
    Map<String, Object> e = object(o, "pickupEvidence");
    require(
        in.str("confirmationId", 1, 64).equals(text(e, "confirmationId")),
        409,
        "PICKUP_CONFIRMATION_REQUIRED",
        "请买家先现场确认");
    require(
        Instant.parse(text(e, "validUntil")).isAfter(Instant.now()),
        409,
        "PICKUP_CONFIRMATION_EXPIRED",
        "买家确认已过期");
    Map<String, Object> p = store.get("pet", text(o, "petId"));
    require(
        text(o, "id").equals(store.occupation(text(p, "id"))),
        409,
        "ORDER_STATE_CONFLICT",
        "库存占用不一致");
    Map<String, Object> q = deliveryQuarantine(p, body, actor.id());
    Instant now = Instant.now();
    e.put("operatorId", actor.id());
    e.put("deliveredAt", now.toString());
    o.put("deliveryQuarantine", catalog.publicQuarantine(q));
    o.put("deliveryOriginalFileIds", strings(q, "originalFileIds"));
    o.put("status", "completed");
    o.put("completedAt", now.toString());
    o.put("pickedUpAt", now.toString());
    o.put("pickup", null);
    p.put("status", "sold");
    p.put("activeOrderId", null);
    store.release(text(p, "id"), text(o, "id"));
    store.save(p);
    store.save(o);
    store.audit(actor.id(), "order.pickup", text(o, "id"));
    return map(
        "orderId",
        o.get("id"),
        "status",
        "completed",
        "pickedUpAt",
        now.toString(),
        "healthGuaranteeExpiresAt",
        o.get("healthGuaranteeExpiresAt"));
  }
}
