package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 订单领域核心：金额、协议签署、商品快照、库存占用与交付状态的事实来源。
 *
 * <p><b>为什么要快照</b>：宠物的价格、照片、检疫信息在成交后仍可能被运营修改。订单一旦创建，
 * 就把 {@code priceSnapshot} / {@code productSnapshot} / {@code shopSnapshot} / {@code agreement}
 * 冻结成副本，之后任何履约、售后、对账都只读快照，不回头读商品表。这样改价不会追溯影响历史订单。
 *
 * <p><b>并发模型</b>：单店只有一把业务锁（{@code BusinessRepository.lock()}），由调用方在事务内先行获取，
 * 所以本类方法内部不再加锁。方法本身不是线程安全的，不能脱离事务直接调用。
 *
 * <p><b>两条履约路径</b>：
 *
 * <ul>
 *   <li>历史线上支付路径：{@code pending_paid} → {@code paid} → {@code completed}，现已关闭支付入口；
 *   <li>到店预约路径：由 {@link AppointmentService} 在本类 {@link #createAppointment} 之上改写状态。
 * </ul>
 *
 * <p>本类不负责权限判定（除 {@link #createAppointment} 的角色断言外），鉴权在 Controller 与
 * {@code AuthService} 完成。
 */
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

  /** 取出订单并校验归属：店长可读全部，买家只能读自己的单；无权限一律按 404 处理，不泄露订单是否存在。 */
  public Map<String, Object> owned(String id, AuthService.Actor actor) {
    return store.ownedOrder(id, actor);
  }

  /**
   * 下单前预览（结算页数据）。
   *
   * <p>返回当前生效的价格、协议版本与内容哈希、门店信息以及 {@code purchaseAllowed} 标记。
   * 前端下单时必须把 {@code productVersion}、{@code expectedAmount}、{@code agreementVersion}、
   * {@code agreementContentHash} 原样回传，服务端据此做三重一致性校验，防止"看到的是旧价格/旧协议"。
   *
   * <p>下架（{@code off}）的宠物直接 404，不向买家暴露草稿；售出/预订中的宠物仍可预览，
   * 但 {@code purchaseAllowed=false} 并给出阻断原因。
   */
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
        catalog.purchasable(p) ? null : "当前宠物不可预约");
  }

  /** 线上购买入口的默认实现：联系人手机号必须是登录账号本人。 */
  public Map<String, Object> create(Map<String, Object> body, AuthService.Actor actor) {
    return create(body, actor, false);
  }

  /** 仅到店预约允许无手机号的微信账号手填联系号码；订单仍归当前账号。 */
  public Map<String, Object> createAppointment(Map<String, Object> body, AuthService.Actor actor) {
    AuthService.role(actor, "buyer");
    Map<String, Object> user = store.get("user", actor.id());
    boolean wechatOnly = Objects.toString(user.get("phone"), "").isBlank()
        && store.list("wechat").stream().anyMatch(w -> actor.id().equals(text(w, "ownerId")));
    return create(body, actor, wechatOnly);
  }

  /**
   * 创建订单（内部统一实现）。
   *
   * <p>校验顺序刻意如此，每步失败都对应一个可区分的前端错误码：
   *
   * <ol>
   *   <li>{@code PET_NOT_AVAILABLE} —— 宠物已被预订、下架，或检疫证明已失效；
   *   <li>{@code PRICE_CHANGED} —— 客户端提交的金额与库中现价不一致（防篡改、防过期页面）；
   *   <li>{@code VERSION_CONFLICT} —— 商品版本号不匹配，说明运营在此期间改过数据；
   *   <li>{@code AGREEMENT_CHANGED} —— 协议版本或内容哈希变化，必须重新阅读并同意；
   *   <li>{@code VALIDATION_ERROR} —— 联系人手机号不是登录本人（预约场景除外）。
   * </ol>
   *
   * <p>落单后立即占用库存（{@code store.occupy}）并把宠物置为 {@code reserved}。
   * 占用与订单写入必须同事务，否则会出现"宠物被锁但没有订单"的僵尸库存。
   *
   * @param manualContact 为 true 时跳过"联系人须为本人手机号"校验，仅微信静默登录账号可置位
   */
  private Map<String, Object> create(Map<String, Object> body, AuthService.Actor actor, boolean manualContact) {
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
    require(manualContact || phone.equals(actor.phone()), 400, "VALIDATION_ERROR", "联系人必须为登录手机号本人");
    Instant now = Instant.now();
    Map<String, Object> s = catalog.shop();
    // 订单号用 "WP" + 32 位大写十六进制（源自 UUID），可读性让位于唯一性；业务上一律用 id 关联。
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
    // 一宠一锁：pet.active_order_id 指向当前订单，release 时带订单号做条件更新。
    store.occupy(text(p, "id"), text(o, "id"));
    p.put("status", "reserved");
    p.put("activeOrderId", o.get("id"));
    store.save(p);
    store.audit(actor.id(), "order.create", text(o, "id"));
    return o;
  }

  /** 该订单的全部售后单（含已结束的），按创建顺序返回；调用方通常只取最新一条。 */
  public List<Map<String, Object>> sales(Map<String, Object> order) {
    return store.list("after_sale").stream()
        .filter(s -> Objects.equals(s.get("orderId"), order.get("id")))
        .toList();
  }

  /**
   * 是否存在未结束的售后单。
   *
   * <p>这些状态视为"已终结"：{@code rejected}、{@code resolved}、{@code refunded}、{@code cancelled}。
   * 其余状态（申请中、审核中、退款中）都会阻断交付与再次申请售后。
   */
  public boolean blocked(Map<String, Object> o) {
    return sales(o).stream()
        .anyMatch(
            s ->
                !List.of("rejected", "resolved", "refunded", "cancelled")
                    .contains(text(s, "status")));
  }

  /**
   * 交付前置条件，三条都通过才允许自提/完成。
   *
   * <p>1) 订单必须处于 {@code paid}；2) 未过自提保留期；3) 没有进行中的售后。
   * 第三条是为防止"申请退款的同时把宠物抱走"。
   */
  public void pickupAllowed(Map<String, Object> o) {
    BusinessRepository.state(o, "paid");
    require(
        !Instant.now().isAfter(Instant.parse(text(o, "pickupDeadlineAt"))),
        409,
        "ORDER_EXPIRED",
        "自提已超期");
    require(!blocked(o), 409, "ORDER_STATE_CONFLICT", "售后处理中不能交付");
  }

  /**
   * 推导当前订单可申请的售后类型与可选处理方式，供前端渲染按钮。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>预约单（含 {@code appointment}）不适用线上售后，由线下协商；
   *   <li>{@code paid} 未交付 → 仅支持"未交付退款"，处理方式为全额退；
   *   <li>{@code completed} 且在健康保障期内 → 支持"健康问题"，可选全额退款或承担治疗费，
   *       若门店开启换宠且尚未退过款，额外允许换宠；
   *   <li>已退满（{@code refundedAmount >= amount}）或有进行中售后时，不再给出任何类型。
   * </ul>
   */
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

  /**
   * 列表/卡片用的订单摘要。
   *
   * <p>{@code availableActions} 是给前端直接渲染按钮用的，服务端已按状态、售后阻断、自提期限算好，
   * 前端不需要再维护一份状态判断逻辑——避免两端规则漂移。
   *
   * <p>{@code statusText} 里的"历史待付款"是刻意命名：支付入口已下线，该状态只可能出现在老数据上。
   */
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

  /**
   * 订单详情（摘要的超集）。
   *
   * <p>{@code admin=true} 时额外下发 {@code userId} 与完整手机号，买家侧只给掩码手机号。
   * {@code pickup} 只在"待自提且无售后阻断"时下发，避免已完成的订单继续暴露自提码。
   */
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

  /**
   * 释放订单占用的宠物库存，并把宠物恢复到可售状态。
   *
   * <p>关键在 {@code store.release(...) > 0} 这个条件：只有确实释放成功（即
   * {@code pet.active_order_id} 还等于本订单）才回写宠物。这样并发下另一笔订单已经接管库存时，
   * 这里不会把别人的锁清掉。
   *
   * <p>恢复状态时排除 {@code sold}（已售出不能复活）和 {@code off}（人工下架的不能自动上架），
   * 其余按检疫证明是否有效决定 {@code on_sale} 还是 {@code off}。
   */
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

  /**
   * 买家现场确认（交付流程的第一半），需要短信验证码。
   *
   * <p>产生的 {@code confirmation} 记录有效期默认 10 分钟，且不能晚于自提截止时间——
   * 取两者较早值，防止"确认还没过期、但订单已过保留期"。
   *
   * <p>同时推导健康保障到期时间：{@code confirmedAt + healthGuaranteeDays}。
   * 注意起算点是买家确认时刻，不是交付时刻。
   */
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

  /**
   * 构建一条"确认记录"，同时校验协议文本版本与短信验证码。
   *
   * <p>四项现场检查（精神、眼鼻、毛况、排泄）必须全部为 true，缺一项就拒绝——
   * 这是宠物活体交易里最容易产生纠纷的部分，做成强确认而不只是提示语。
   *
   * <p>{@code scope} 区分场景（自提确认 / 换宠确认），并写进记录，防止两种确认互相顶替。
   * 换宠场景额外要求 {@code replacementPetId}。
   */
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

  /**
   * 校验自提凭证：{@code pickupCode} 与 {@code qrPayload} 必须二选一，不能同时给也不能都不给。
   *
   * <p>这条"异或"约束是为了让凭证类型明确——两者都传时无法判断该信任哪一个，容易被构造出
   * "码对但二维码不对"的歧义请求。{@code code != qr} 正是异或。
   */
  public static boolean matchesCode(Map<String, Object> pickup, Map<String, Object> body) {
    boolean code = body.get("pickupCode") != null, qr = body.get("qrPayload") != null;
    return code != qr
        && (code
            ? Objects.equals(pickup.get("code"), body.get("pickupCode"))
            : Objects.equals(pickup.get("qrPayload"), body.get("qrPayload")));
  }

  /**
   * 店员扫码/输码查单：始终返回 200 结构，用 {@code pickupAllowed} 与 {@code blockedReason}
   * 表达能否交付，而不是抛 409。
   *
   * <p>这样门店端可以先看到订单和人，再决定怎么处理（比如先补做买家确认），
   * 而不至于因为一个异常看不到任何上下文。
   *
   * <p>查找时还要求 {@code status=paid}，已完成或已退款的单不会被扫出来。
   */
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

  /**
   * 解析本次交付使用的检疫证明：请求里带了就用请求的（店长现场上传/核验），否则回落到宠物档案里的。
   *
   * <p>无论来源如何，最后都必须通过 {@code validQuarantine} 校验，失败返回 422
   * {@code QUARANTINE_REQUIRED}——活体交易不允许无有效检疫记录就交付。
   */
  public Map<String, Object> deliveryQuarantine(
      Map<String, Object> pet, Map<String, Object> body, String admin) {
    Map<String, Object> q =
        body.get("deliveryQuarantine") == null
            ? object(pet, "quarantine")
            : catalog.quarantine(object(body, "deliveryQuarantine"), admin);
    require(catalog.validQuarantine(map("quarantine", q)), 422, "QUARANTINE_REQUIRED", "交付检疫证明无效");
    return q;
  }

  /**
   * 完成交付（交付流程的第二半，由店员执行）。
   *
   * <p>必须同时满足：订单可交付、凭证匹配、买家确认未过期且 {@code confirmationId} 对得上、
   * 库存仍属于本单、检疫证明有效。全部通过后才把订单置 {@code completed}、宠物置 {@code sold}。
   *
   * <p>这里把 {@code pickup} 清空，是为了让自提码在完成瞬间失效，防止重复扫码。
   */
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
