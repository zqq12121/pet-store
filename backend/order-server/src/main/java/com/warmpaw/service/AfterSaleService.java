package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;
import static com.warmpaw.common.TimeRange.parseInstant;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 售后服务：申请、审核、退回验收、换宠与退款编排。
 *
 * <p><b>售后单与退款单是两张表</b>。售后单记录"人和店之间的协商过程"（谁申请、什么理由、诊断材料、
 * 审核结论、退回记录、时间线），退款单（{@code refund}）只记资金动作，由 {@link PaymentService} 创建并异步执行。
 * 分开的原因是：一次售后可能不产生退款（驳回、换宠），一次退款也可能来自非售后场景（超时关单）。
 * 把资金状态硬塞进售后单会让两边都变脏。
 *
 * <p><b>状态机</b>：
 *
 * <pre>
 *   pending_review ──reject──▶ rejected
 *        │
 *        └─approve─┬─ 未交付 + 全额退款 ──▶ refunding（已发起退款）
 *                  └─ 已交付 ──┬─ 全额退款 ──▶ awaiting_return ──验收──▶ refunding
 *                              └─ 换宠     ──▶ awaiting_return ──验收──▶ awaiting_exchange
 *                                                                          ├─ deliverExchange ──▶ resolved
 *                                                                          └─ exchangeRefund  ──▶ refunding
 * </pre>
 *
 * <p><b>关键约束</b>：
 *
 * <ul>
 *   <li>退回的宠物一律置 {@code off}，绝不因退款自动恢复可售——健康问题退回的宠物不能再次出售；
 *   <li>审核方案必须与买家申请的方案一致，店长只能在金额上收紧，不能单方面改类型；
 *   <li>换宠只支持"未退款 + 同价"，避免出现找零和差价结算。
 * </ul>
 *
 * <p>与其他服务的关系：订单状态由 {@link OrderService} 维护，本类只读取；
 * 资金动作全部委托 {@link PaymentService}。
 */
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

  /** 取出售后单并校验归属；店长可读全部，买家只能读自己提交的。越权统一按 404 处理。 */
  public Map<String, Object> owned(String id, AuthService.Actor a) {
    Map<String, Object> s = store.get("after_sale", id);
    require(
        a != null && (a.role().equals("admin") || a.id().equals(text(s, "ownerId"))),
        404,
        "RESOURCE_NOT_FOUND",
        "售后不存在或不可访问");
    return s;
  }

  /**
   * 售后详情。买家视角不含订单全量；店长视角内联完整订单（{@code admin=true}），
   * 便于在一屏内判断该不该批——否则店长要在两个接口之间来回切。
   */
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

  /**
   * 追加一条时间线事件。
   *
   * <p>时间线是追加式的（取出旧列表再 add 新元素后整体写回），不做原地修改，
   * 这样每一步都有不可变的 {@code occurredAt} 和操作方类型（buyer/admin），可直接展示给用户。
   *
   * <p>注意：本方法只改内存对象，调用方需自行 {@code store.save(s)}。
   */
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

  /**
   * 买家提交售后申请。
   *
   * <p>资格不是在这里现算的，而是直接复用 {@link OrderService#eligibility}——保证"前端看到的按钮"
   * 和"服务端允许的类型"来自同一份规则，不会出现界面能点、接口报错。
   *
   * <p>两类申请的差异：
   *
   * <ul>
   *   <li>{@code refund_before_pickup}（未交付）：只能全额退款，不允许带诊断信息，也不允许指定金额；
   *   <li>{@code health_issue}（已交付）：必须提供诊断时间与 1~5 张诊断材料。诊断时间要落在
   *       {@code [pickedUpAt, min(now, healthGuaranteeExpiresAt)]} 区间内——既不能早于提宠，
   *       也不能晚于保障期，还不能是未来时间。
   * </ul>
   *
   * <p>图片不做"仅登记 id"，而是逐张调 {@code catalog.asset} 校验归属与用途，
   * 防止有人拿别人订单的图片来凑证据。
   */
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
    // 只有"承担治疗费"才由买家提出金额，其余方案金额由服务端算，避免买家自己定价。
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

  /**
   * 店长审核：通过或驳回。
   *
   * <p>通过时必须指定 {@code resolution}，且必须与买家申请的一致——这一条刻意写死，
   * 因为"申请全额退款、店长批成换宠"这类单方面变更会直接引发投诉。
   * 店长能做的是在 {@code treatment_share} 场景下把金额往下压（不超过申请额、不超过可退余额）。
   *
   * <p>通过后的分流：
   *
   * <ul>
   *   <li>{@code treatment_share}：立即发起部分退款，不走退回流程（宠物通常还在买家手里继续治疗）；
   *   <li>全额退款 / 换宠：已提宠的进入 {@code awaiting_return} 等门店验收退回；
   *       未提宠的直接发起全额退款。
   * </ul>
   *
   * <p>驳回时禁止携带 {@code resolution} 与 {@code approvedAmount}，防止前端把默认值一起提交，
   * 造成"驳回但批准了方案"的脏数据。
   */
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
        // 上限取三者最小：买家申请额、订单可退余额，避免超退。
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

  /**
   * 门店验收买家退回的宠物。
   *
   * <p>必须先有 {@code awaiting_return} 状态才能验收（换宠和全额退款都走这一步）。
   * 验收后宠物一律置 {@code off} 并解除占用——这是全类最重要的一条约束：
   * <b>因为健康问题退回的宠物不能再卖</b>，是否重新上架必须由店长人工判断，不能由系统自动恢复。
   *
   * <p>验收完按已批方案分流：换宠进入 {@code awaiting_exchange}，退款立即发起。
   * {@code conditionNotes} 必填，记录退回时的实际状态，是后续纠纷的证据。
   */
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

  /**
   * 换宠开关。门店级配置项（{@code shop.exchangeEnabled}），关闭时所有换宠入口统一返回
   * 422 {@code EXCHANGE_UNAVAILABLE}，而不是各自判断。
   */
  public void exchangeEnabled() {
    require(
        Boolean.TRUE.equals(catalog.shop().get("exchangeEnabled")),
        422,
        "EXCHANGE_UNAVAILABLE",
        "门店暂未开通换宠");
  }

  /**
   * 为换宠选择替换宠物并占用库存。
   *
   * <p>约束：不能换原宠；必须同价（换宠不做差价结算）；订单必须未退过款；
   * 替换宠必须处于可售状态。
   *
   * <p>幂等处理：如果本次选的还是上次那只、且仍处于 {@code reserved}，直接返回原记录，
   * 不重复占用。否则先 {@link #releaseReplacement} 释放上一只，再占用新的——
   * 顺序不能反，否则会出现两只宠物同时被本单锁住。
   */
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

  /**
   * 计算换宠确认的 {@code scope} 并顺带校验前置条件。
   *
   * <p>{@code scope} 形如 {@code 售后单id:换宠id:替换宠id}，会被写进确认记录并参与短信验证码校验。
   * 这样"买家确认的是 A 宠物"这件事被绑定到具体对象上，事后换宠不会复用旧确认。
   */
  public String exchangeScope(Map<String, Object> s) {
    Map<String, Object> e = object(s, "exchange");
    exchangeEnabled();
    BusinessRepository.state(s, "awaiting_exchange");
    require("reserved".equals(text(e, "status")), 409, "ORDER_STATE_CONFLICT", "没有有效换宠方案");
    return text(s, "id") + ":" + text(e, "id") + ":" + text(e, "replacementPetId");
  }

  /**
   * 买家现场确认替换宠（短信验证）。复用 {@link OrderService#confirmation}，
   * 但 purpose 用 {@code exchange_confirm}、scope 带换宠三元组，与自提确认互不通用。
   */
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

  /**
   * 交付替换宠，完成换宠。
   *
   * <p>与自提流程同构：校验确认未过期、库存归属、检疫证明有效，然后把替换宠置 {@code sold}、
   * 售后单置 {@code resolved}。
   *
   * <p>注意这里复用订单 id 作为库存占用方（{@code store.release(petId, orderId)}）——
   * 换宠不新建订单，原订单始终是这次交易的唯一凭证。
   */
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

  /**
   * 释放当前换宠方案占用的替换宠库存，并把换宠记录标记为 {@code cancelled}。
   *
   * <p>用 {@code release(...) > 0} 做条件释放：只有占用方确实是本订单时才回写宠物，
   * 防止误清掉别的订单的锁。同时清掉确认 id 与确认时间，使旧的买家确认立即失效。
   *
   * <p>调用场景：改选另一只替换宠、协商改为退款。本方法只改内存对象，不落库。
   */
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

  /**
   * 换宠协商破裂，转为全额退款。
   *
   * <p>适用场景：已验收原宠退回、但双方对替换宠谈不拢。因为钱一直没退，这里直接发起全额退款，
   * 并强制要求买家书面同意（{@code buyerConsentFileIds} 必填 1~5 份）——
   * 从"换"改成"退"是重大变更，必须有可追溯的同意材料。
   *
   * <p>先把 {@code approvedResolution} 改成 {@code full_refund} 再发起退款，
   * 保证退款单与售后单的口径一致。
   */
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
