package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;
import static com.warmpaw.common.TimeRange.parseInstant;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 售后与财务退款分开记录；退回宠物保持下架，不能因退款自动重新出售。 */
@Service
public class AfterSaleService {
  private final BusinessRepository store;
  private final CatalogReader catalog;
  private final OrderService orders;
  private final PaymentService payments;

  public AfterSaleService(
      BusinessRepository store,
      CatalogReader catalog,
      OrderService orders,
      PaymentService payments) {
    this.store = store;
    this.catalog = catalog;
    this.orders = orders;
    this.payments = payments;
  }

  public Map<String, Object> owned(String id, AuthService.Actor a) {
    Map<String, Object> s = store.get("after_sale", id);
    require(
        a != null && (a.role().equals("admin") || a.id().equals(text(s, "ownerId"))),
        404,
        "RESOURCE_NOT_FOUND",
        "售后不存在或不可访问");
    return s;
  }

  public Map<String, Object> detail(Map<String, Object> s, boolean admin) {
    Map<String, Object> r =
        CatalogReader.select(
            s,
            "id,orderId,type,status,requestedResolution,approvedResolution,createdAt,updatedAt,version,reason,diagnosisAt,diagnosisFileIds,evidenceFileIds,requestedAmount,approvedAmount,reviewReason,reviewedAt,returnRecord,exchange,timeline");
    r.put(
        "refund",
        s.get("refundId") == null
            ? null
            : CatalogReader.select(
                store.get("refund", text(s, "refundId")),
                "id,refundNo,afterSaleId,reason,amount,status,createdAt,succeededAt,failureReason"));
    if (admin) r.put("order", orders.detail(store.get("order", text(s, "orderId")), true));
    return r;
  }

  public void timeline(
      Map<String, Object> s, String event, String label, String note, String actor) {
    List<Map<String, Object>> t = new ArrayList<>(objects(s, "timeline"));
    t.add(
        map(
            "event",
            event,
            "label",
            label,
            "note",
            note,
            "occurredAt",
            Instant.now().toString(),
            "actorType",
            actor));
    s.put("timeline", t);
  }

  public Map<String, Object> create(
      Map<String, Object> o, Map<String, Object> body, AuthService.Actor actor) {
    Input in =
        new Input(
            body,
            "type,requestedResolution,reason,diagnosisAt,diagnosisFileIds,evidenceFileIds,requestedAmount");
    require(!orders.blocked(o), 409, "AFTER_SALE_EXISTS", "已有进行中的售后");
    String type = in.choice("type", "refund_before_pickup,health_issue"),
        resolution = in.choice("requestedResolution", "full_refund,exchange,treatment_share");
    if (resolution.equals("exchange")) exchangeEnabled();
    require(
        strings(orders.eligibility(o), "allowedTypes").contains(type),
        422,
        "AFTER_SALE_WINDOW_EXPIRED",
        "当前状态或保障期限不允许申请，请联系店主");
    require(
        strings(orders.eligibility(o), "allowedResolutions").contains(resolution),
        400,
        "VALIDATION_ERROR",
        "当前不支持此处理方案");
    String orderId = text(o, "id");
    List<String> diagnosis = List.of(),
        evidence = in.has("evidenceFileIds") ? in.ids("evidenceFileIds", 0, 9) : List.of();
    Instant diagnosisAt = null;
    if (type.equals("health_issue")) {
      diagnosisAt = parseInstant(in.str("diagnosisAt", 1, 40));
      require(
          !diagnosisAt.isBefore(Instant.parse(text(o, "pickedUpAt")))
              && !diagnosisAt.isAfter(Instant.now())
              && !diagnosisAt.isAfter(Instant.parse(text(o, "healthGuaranteeExpiresAt"))),
          422,
          "AFTER_SALE_WINDOW_EXPIRED",
          "诊断时间不在保障期内");
      diagnosis = in.ids("diagnosisFileIds", 1, 5);
    } else
      require(
          !in.has("diagnosisAt") && !in.has("diagnosisFileIds") && resolution.equals("full_refund"),
          400,
          "VALIDATION_ERROR",
          "待自提仅支持全额退款");
    for (String id : diagnosis) catalog.asset(id, "diagnosis", actor.id(), orderId);
    for (String id : evidence) catalog.asset(id, "after_sale_evidence", actor.id(), orderId);
    Long amount =
        resolution.equals("treatment_share")
            ? in.integer("requestedAmount", 1, number(o, "amount") - number(o, "refundedAmount"))
            : null;
    require(
        resolution.equals("treatment_share") || !in.has("requestedAmount"),
        400,
        "VALIDATION_ERROR",
        "此方案不能指定退款金额");
    Map<String, Object> s =
        store.create(
            "after_sale",
            actor.id(),
            null,
            map(
                "orderId",
                orderId,
                "petId",
                o.get("petId"),
                "type",
                type,
                "status",
                "pending_review",
                "requestedResolution",
                resolution,
                "approvedResolution",
                null,
                "reason",
                in.str("reason", 1, 1000),
                "diagnosisAt",
                diagnosisAt == null ? null : diagnosisAt.toString(),
                "diagnosisFileIds",
                diagnosis,
                "evidenceFileIds",
                evidence,
                "requestedAmount",
                amount,
                "approvedAmount",
                null,
                "reviewReason",
                null,
                "reviewedAt",
                null,
                "returnRecord",
                null,
                "exchange",
                null,
                "refundId",
                null,
                "timeline",
                new ArrayList<>(),
                "updatedAt",
                Instant.now().toString()));
    timeline(s, "created", "已提交申请", null, "buyer");
    store.save(s);
    return s;
  }

  public Map<String, Object> review(
      Map<String, Object> s, Map<String, Object> body, AuthService.Actor actor) {
    Input in = new Input(body, "version,decision,reason,resolution,approvedAmount");
    BusinessRepository.version(s, in.integer("version", 1, Integer.MAX_VALUE));
    BusinessRepository.state(s, "pending_review");
    String decision = in.choice("decision", "approve,reject"), reason = in.str("reason", 1, 1000);
    s.put("reviewReason", reason);
    s.put("reviewedAt", Instant.now().toString());
    Map<String, Object> o = store.get("order", text(s, "orderId"));
    if (decision.equals("reject")) {
      require(
          !in.has("resolution") && !in.has("approvedAmount"),
          400,
          "VALIDATION_ERROR",
          "驳回不能附带处理方案");
      s.put("status", "rejected");
    } else {
      String resolution = in.choice("resolution", "full_refund,exchange,treatment_share");
      require(
          resolution.equals(text(s, "requestedResolution")),
          400,
          "VALIDATION_ERROR",
          "审核方案必须与申请一致");
      if (resolution.equals("exchange")) exchangeEnabled();
      s.put("approvedResolution", resolution);
      if (resolution.equals("treatment_share")) {
        long amount =
            in.integer(
                "approvedAmount",
                1,
                Math.min(
                    number(s, "requestedAmount"),
                    number(o, "amount") - number(o, "refundedAmount")));
        s.put("approvedAmount", amount);
        payments.refund(o, s, amount, "treatment_share");
      } else {
        require(!in.has("approvedAmount"), 400, "VALIDATION_ERROR", "此方案金额由服务端计算");
        s.put("approvedAmount", number(o, "amount") - number(o, "refundedAmount"));
        if (o.get("pickedUpAt") != null) s.put("status", "awaiting_return");
        else
          payments.refund(
              o, s, number(o, "amount") - number(o, "refundedAmount"), "customer_request");
      }
    }
    timeline(s, decision, decision.equals("approve") ? "审核通过" : "申请已驳回", reason, "admin");
    store.save(s);
    store.audit(actor.id(), "after_sale.review", text(s, "id"));
    return s;
  }

  public Map<String, Object> returned(
      Map<String, Object> s, Map<String, Object> body, AuthService.Actor actor) {
    Input in = new Input(body, "version,received,conditionNotes,evidenceFileIds");
    BusinessRepository.version(s, in.integer("version", 1, Integer.MAX_VALUE));
    BusinessRepository.state(s, "awaiting_return");
    in.yes("received");
    List<String> evidence = in.has("evidenceFileIds") ? in.ids("evidenceFileIds", 0, 9) : List.of();
    for (String id : evidence)
      catalog.asset(id, "after_sale_evidence", actor.id(), text(s, "orderId"));
    Map<String, Object> o = store.get("order", text(s, "orderId")),
        p = store.get("pet", text(o, "petId"));
    s.put(
        "returnRecord",
        map(
            "id",
            id("return"),
            "receivedAt",
            Instant.now().toString(),
            "conditionNotes",
            in.str("conditionNotes", 1, 2000),
            "evidenceFileIds",
            evidence,
            "operatorId",
            actor.id()));
    p.put("status", "off");
    p.put("activeOrderId", null);
    store.save(p);
    if ("exchange".equals(text(s, "approvedResolution"))) s.put("status", "awaiting_exchange");
    else payments.refund(o, s, number(o, "amount") - number(o, "refundedAmount"), "health_issue");
    timeline(s, "returned", "门店已验收退回", null, "admin");
    store.save(s);
    return s;
  }

  public void exchangeEnabled() {
    require(
        Boolean.TRUE.equals(catalog.shop().get("exchangeEnabled")),
        422,
        "EXCHANGE_UNAVAILABLE",
        "门店暂未开通换宠");
  }

  public Map<String, Object> exchange(
      Map<String, Object> s, Map<String, Object> body, AuthService.Actor actor) {
    exchangeEnabled();
    Input in = new Input(body, "version,replacementPetId");
    BusinessRepository.version(s, in.integer("version", 1, Integer.MAX_VALUE));
    BusinessRepository.state(s, "awaiting_exchange");
    require(s.get("returnRecord") != null, 409, "RETURN_REQUIRED", "请先验收原宠退回");
    String id = in.str("replacementPetId", 1, 64);
    Map<String, Object> o = store.get("order", text(s, "orderId")), p = store.get("pet", id);
    require(!id.equals(text(o, "petId")), 400, "VALIDATION_ERROR", "替换宠不能是原宠");
    require(
        number(p, "priceAmount") == number(o, "amount") && number(o, "refundedAmount") == 0,
        422,
        "EXCHANGE_PRICE_MISMATCH",
        "仅支持未退款订单的同价换宠");
    Map<String, Object> old = object(s, "exchange");
    if (id.equals(text(old, "replacementPetId")) && "reserved".equals(text(old, "status")))
      return old;
    require(catalog.purchasable(p), 409, "PET_NOT_AVAILABLE", "替换宠不可购买");
    releaseReplacement(s);
    store.occupy(id, text(o, "id"));
    p.put("status", "reserved");
    p.put("activeOrderId", o.get("id"));
    store.save(p);
    Map<String, Object> e =
        map(
            "id",
            com.warmpaw.common.Json.id("exchange"),
            "replacementPetId",
            id,
            "productSnapshot",
            catalog.detail(p),
            "status",
            "reserved",
            "confirmationId",
            null,
            "confirmedAt",
            null,
            "deliveredAt",
            null,
            "deliveryQuarantine",
            null);
    s.put("exchange", e);
    s.put("exchangeEvidence", null);
    timeline(s, "exchange_reserved", "已选择替换宠", null, "admin");
    store.save(s);
    return e;
  }

  public String exchangeScope(Map<String, Object> s) {
    Map<String, Object> e = object(s, "exchange");
    exchangeEnabled();
    BusinessRepository.state(s, "awaiting_exchange");
    require("reserved".equals(text(e, "status")), 409, "ORDER_STATE_CONFLICT", "没有有效换宠方案");
    return text(s, "id") + ":" + text(e, "id") + ":" + text(e, "replacementPetId");
  }

  public Map<String, Object> confirmExchange(
      Map<String, Object> s, Map<String, Object> body, AuthService.Actor actor) {
    String scope = exchangeScope(s);
    require(
        Objects.equals(body.get("replacementPetId"), object(s, "exchange").get("replacementPetId")),
        409,
        "VERSION_CONFLICT",
        "替换宠已变化");
    Map<String, Object> o = store.get("order", text(s, "orderId")),
        e = orders.confirmation(body, actor, text(o, "contactPhone"), "exchange_confirm", scope);
    store.create("confirmation", actor.id(), null, e);
    s.put("exchangeEvidence", e);
    object(s, "exchange").put("confirmationId", e.get("confirmationId"));
    object(s, "exchange").put("confirmedAt", e.get("confirmedAt"));
    store.save(s);
    return CatalogReader.select(e, "confirmationId,confirmedAt,validUntil");
  }

  public Map<String, Object> deliverExchange(
      Map<String, Object> s, Map<String, Object> body, AuthService.Actor actor) {
    exchangeScope(s);
    Input in = new Input(body, "version,confirmationId,quarantineVerified,deliveryQuarantine");
    BusinessRepository.version(s, in.integer("version", 1, Integer.MAX_VALUE));
    in.yes("quarantineVerified");
    Map<String, Object> e = object(s, "exchangeEvidence"), x = object(s, "exchange");
    require(
        in.str("confirmationId", 1, 64).equals(text(e, "confirmationId")),
        409,
        "PICKUP_CONFIRMATION_REQUIRED",
        "请买家确认替换宠");
    require(
        Instant.parse(text(e, "validUntil")).isAfter(Instant.now()),
        409,
        "PICKUP_CONFIRMATION_EXPIRED",
        "确认已过期");
    Map<String, Object> p = store.get("pet", text(x, "replacementPetId"));
    require(
        text(s, "orderId").equals(store.occupation(text(p, "id"))),
        409,
        "ORDER_STATE_CONFLICT",
        "替换宠占用异常");
    Map<String, Object> q = orders.deliveryQuarantine(p, body, actor.id());
    x.put("deliveryQuarantine", catalog.publicQuarantine(q));
    s.put("exchangeOriginalFileIds", strings(q, "originalFileIds"));
    x.put("status", "completed");
    x.put("deliveredAt", Instant.now().toString());
    p.put("status", "sold");
    p.put("activeOrderId", null);
    store.release(text(p, "id"), text(s, "orderId"));
    store.save(p);
    s.put("status", "resolved");
    timeline(s, "exchange_delivered", "替换宠已交付", null, "admin");
    store.save(s);
    return s;
  }

  private void releaseReplacement(Map<String, Object> s) {
    Map<String, Object> x = object(s, "exchange");
    if (!"reserved".equals(text(x, "status"))) return;
    Map<String, Object> p = store.get("pet", text(x, "replacementPetId"));
    if (store.release(text(p, "id"), text(s, "orderId")) > 0) {
      p.put("status", catalog.validQuarantine(p) ? "on_sale" : "off");
      p.put("activeOrderId", null);
      store.save(p);
    }
    x.put("status", "cancelled");
    s.put("exchangeEvidence", null);
    x.put("confirmationId", null);
    x.put("confirmedAt", null);
  }

  public Map<String, Object> exchangeRefund(
      Map<String, Object> s, Map<String, Object> body, AuthService.Actor actor) {
    Input in = new Input(body, "version,reason,buyerConsentFileIds");
    BusinessRepository.version(s, in.integer("version", 1, Integer.MAX_VALUE));
    BusinessRepository.state(s, "awaiting_exchange");
    require(s.get("returnRecord") != null, 409, "RETURN_REQUIRED", "原宠尚未退回");
    for (String id : in.ids("buyerConsentFileIds", 1, 5))
      catalog.asset(id, "after_sale_evidence", actor.id(), text(s, "orderId"));
    releaseReplacement(s);
    s.put("buyerConsentFileIds", body.get("buyerConsentFileIds"));
    s.put("approvedResolution", "full_refund");
    Map<String, Object> o = store.get("order", text(s, "orderId"));
    payments.refund(o, s, number(o, "amount") - number(o, "refundedAmount"), "health_issue");
    timeline(s, "exchange_refund", "协商改为退款", in.str("reason", 1, 1000), "admin");
    store.save(s);
    return s;
  }
}
