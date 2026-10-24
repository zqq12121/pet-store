package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 支付与退款：先落库"意图"，再由提交后的工作器访问微信平台。
 *
 * <p><b>为什么不在请求线程里调平台</b>：微信下单/退款接口可能超时，超时时"到底成功没有"是未知的。
 * 如果在事务里同步调用，一旦平台成功而本地回滚，就会出现"钱收了、单没了"。所以这里是典型的两阶段：
 * 事务内只写 {@code payment}/{@code refund} 记录（状态 {@code pending}/{@code processing}），
 * 提交后由 {@link PaymentWorker} 拿着记录去调平台，失败可重试、结果可查询。
 *
 * <p><b>失败与未知都不释放库存</b>——释放库存只发生在明确关单（{@link #closed}）或退款成功时。
 * 这条规则避免"平台其实收钱了，但本地以为失败把宠物卖给了别人"。
 *
 * <p><b>幂等</b>：用平台侧唯一号做键表。{@code payment_fact} 以 {@code transaction_id} 为键，
 * {@code refund_fact} 以 {@code refund_id} 为键。同一笔流水重复回调只会命中已存在记录，
 * 不会重复改状态或重复加钱。
 *
 * <p><b>当前业务状态</b>：线上支付入口已停用，{@link #capabilities} 对所有场景返回 disabled，
 * 新预约一律走线下收款。本类保留完整实现，用于历史订单退款与将来重新开通。
 */
@Service
public class PaymentService {
  private final BusinessRepository store;
  private final OrderService orders;
  private final AuthService auth;
  private final WechatGateway gateway;

  public PaymentService(
      BusinessRepository store, OrderService orders, AuthService auth, WechatGateway gateway) {
    this.store = store;
    this.orders = orders;
    this.auth = auth;
    this.gateway = gateway;
  }

  /**
   * 支付能力发现接口。
   *
   * <p>即使全部关闭也返回 200 和完整结构（每个场景带 {@code disabledReason}），
   * 让前端能直接展示"为什么不能付"，而不是靠 404 猜。
   */
  public Map<String, Object> capabilities() {
    return map(
        "currency",
        "CNY",
        "methods",
        List.of("jsapi", "h5").stream()
            .map(
                s ->
                    map(
                        "scene",
                        s,
                        "enabled",
                        false,
                        "disabledReason",
                        "线上支付已停用，请预约到店付款"))
            .toList());
  }

  /** 取订单关联的支付单实体；订单上的 {@code payment.paymentId} 为空时返回 null（表示从未发起支付）。 */
  public Map<String, Object> payment(Map<String, Object> o) {
    String id = text(object(o, "payment"), "paymentId");
    return id == null ? null : store.get("payment", id);
  }

  /**
   * 发起支付（只写意图，不调平台）。
   *
   * <p>几个刻意的判断：
   *
   * <ul>
   *   <li>已存在未关闭的支付单时直接返回它，不新建——重复点击"支付"不应产生多张单；
   *   <li>但若新请求换了场景（jsapi ↔ h5），返回 409 要求先关闭旧单，因为两种场景的
   *       调起参数完全不同，不能混用；
   *   <li>JSAPI 需要该用户在该公众号下的 openid，没有就 409 {@code WECHAT_AUTH_REQUIRED}，
   *       让前端先走授权，而不是等到调起时才失败；
   *   <li>{@code returnPath} 会过 {@code ProviderSupport.safePath} 白名单，
   *       防止把站外地址塞进支付回跳造成开放重定向。
   * </ul>
   *
   * <p>{@code outTradeNo} 同时作为商户订单号与记录的业务键，32 位无连字符 UUID。
   */
  public Map<String, Object> begin(Map<String, Object> o, Map<String, Object> body) {
    Input in = new Input(body, "scene,returnPath");
    String scene = in.choice("scene", "jsapi,h5");
    if (in.has("returnPath")) com.warmpaw.common.ProviderSupport.safePath(in.str("returnPath", 1, 500));
    BusinessRepository.state(o, "pending_paid");
    require(
        !Instant.now().isAfter(Instant.parse(text(o, "expiresAt"))), 409, "ORDER_EXPIRED", "付款已超期");
    require(auth.local() || gateway.enabled(scene), 422, "PAYMENT_SCENE_UNAVAILABLE", "支付场景未开通");
    Map<String, Object> p = payment(o);
    if (p != null && !"closed".equals(text(p, "status"))) {
      require(scene.equals(text(p, "scene")), 409, "PAYMENT_IN_PROGRESS", "请等待旧支付单关闭后再切换场景");
      return p;
    }
    if (!auth.local() && scene.equals("jsapi"))
      require(
          store.byKey("wechat", gateway.appId() + ":" + o.get("ownerId")) != null,
          409,
          "WECHAT_AUTH_REQUIRED",
          "请先完成微信授权");
    String out = UUID.randomUUID().toString().replace("-", "");
    p =
        store.create(
            "payment",
            text(o, "ownerId"),
            out,
            map(
                "outTradeNo",
                out,
                "orderId",
                o.get("id"),
                "scene",
                scene,
                "status",
                "pending",
                "expiresAt",
                o.get("expiresAt"),
                // 两个标志位区分"试过调平台没有"和"平台是否受理"，是工作器重试判断的依据。
                "providerCreated",
                false,
                "providerAttempted",
                false,
                "lastQueryAt",
                null));
    o.put(
        "payment",
        map("paymentId", p.get("id"), "status", "pending", "paidAmount", 0, "paidAt", null));
    store.save(o);
    return p;
  }

  /**
   * 支付单的对外视图。
   *
   * <p>{@code invokeParams}（JSAPI 调起参数）与 {@code h5Url} 只在 {@code pending} 且未过期时下发，
   * 过期后连调起参数都不给，避免前端拿着过期参数反复尝试。
   */
  public Map<String, Object> view(Map<String, Object> p) {
    Map<String, Object> r =
        map(
            "paymentId",
            p.get("id"),
            "status",
            p.get("status"),
            "scene",
            p.get("scene"),
            "expiresAt",
            p.get("expiresAt"));
    if ("pending".equals(text(p, "status"))
        && Instant.parse(text(p, "expiresAt")).isAfter(Instant.now())) {
      if (p.containsKey("invokeParams")) r.put("invokeParams", p.get("invokeParams"));
      if (p.containsKey("h5Url")) r.put("h5Url", p.get("h5Url"));
    }
    return r;
  }

  /**
   * 支付状态轮询。
   *
   * <p>{@code nextPollAfter} 是服务端给客户端的建议间隔（秒）：订单还在待付/关单中时 2 秒，
   * 一旦进入终态就返回 0 表示不必再轮询。把节奏交给服务端，前端就不用写死定时器。
   */
  public Map<String, Object> status(Map<String, Object> o) {
    Map<String, Object> p = object(o, "payment");
    return map(
        "orderId",
        o.get("id"),
        "paymentId",
        p.get("paymentId"),
        "status",
        p.get("status"),
        "paidAmount",
        p.get("paidAmount"),
        "paidAt",
        p.get("paidAt"),
        "orderStatus",
        o.get("status"),
        "nextPollAfter",
        List.of("pending_paid", "closing").contains(text(o, "status")) ? 2 : 0);
  }

  /**
   * 买家取消支付：订单先进入 {@code closing}（关单中），而不是直接 cancelled。
   *
   * <p>中间态存在的原因：如果本地已经生成过支付单，必须等平台确认关单才能算真正取消
   * （见 {@link #closed}）。只有从未发起支付的订单才能立刻关单。
   */
  public void cancel(Map<String, Object> o, String reason) {
    BusinessRepository.state(o, "pending_paid");
    o.put("status", "closing");
    o.put("cancelReason", reason);
    store.save(o);
    if (payment(o) == null) closed(o);
  }

  /**
   * 确认关单并释放库存。
   *
   * <p>两道闸：已经支付成功的单不能关（先走退款）；有支付单但平台状态还不是 closed 的，
   * 返回 409 {@code PAYMENT_IN_PROGRESS}，等 {@link PaymentWorker} 查询到平台关单后再调一次。
   *
   * <p>这是仅有的两个释放库存的入口之一（另一个是退款成功后），因为只有这里能确定"钱没收到"。
   */
  public void closed(Map<String, Object> o) {
    Map<String, Object> p = payment(o);
    if (p != null && "succeeded".equals(text(p, "status"))) return;
    require(
        p == null || "closed".equals(text(p, "status")), 409, "PAYMENT_IN_PROGRESS", "尚未确认平台关单");
    o.put("status", "cancelled");
    o.put("closedAt", Instant.now().toString());
    o.put("cancelledAt", Instant.now().toString());
    if (p != null) object(o, "payment").put("status", "closed");
    orders.release(o);
    store.save(o);
  }

  /**
   * 处理支付成功事实（回调或主动查询都会走到这里）。
   *
   * <p><b>三重金额校验</b>：{@code trade_state=SUCCESS}、商户订单号一致、{@code total} 与
   * {@code payer_total} 都等于订单金额，币种为 CNY。任一不符就 400，宁可人工介入也不能入账错金额。
   *
   * <p><b>幂等</b>：先用 {@code transaction_id} 查 {@code payment_fact}。已存在且指向同一支付单
   * → 直接返回（重复回调）；已存在但指向别的支付单 → 409 流水冲突（有人拿同一笔流水套两个单）。
   *
   * <p><b>迟到支付的补偿</b>（{@code conflict} 分支）：以下任一情况说明这笔钱不该收——
   * 订单已不在待付/关单中、订单有取消原因、支付时间晚于订单过期时间、库存已被别的订单占用。
   * 此时仍然记录收款，并<b>立即整额退款</b>（原因 {@code payment_conflict}），而不是把订单改成已付。
   * 这比"拒绝回调"安全：钱在平台上，必须主动退回去。
   *
   * <p>正常路径下生成 8 位自提码与二维码 payload，两者都绑定在 {@code pickup} 上，
   * 截止时间就是自提保留期。
   */
  public void paid(Map<String, Object> p, Map<String, Object> fact) {
    Map<String, Object> o = store.get("order", text(p, "orderId"));
    Map<String, Object> amount = object(fact, "amount");
    require(
        "SUCCESS".equals(text(fact, "trade_state"))
            && text(p, "outTradeNo").equals(text(fact, "out_trade_no"))
            && number(amount, "total") == number(o, "amount")
            && "CNY".equals(text(amount, "currency"))
            && (!amount.containsKey("payer_total")
                || number(amount, "payer_total") == number(o, "amount")),
        400,
        "INVALID_ARGUMENT",
        "支付金额或状态不匹配");
    String tx = text(fact, "transaction_id");
    require(tx != null && !tx.isBlank(), 400, "INVALID_ARGUMENT", "支付流水缺失");
    Map<String, Object> existing = store.byKey("payment_fact", tx);
    if (existing != null) {
      require(p.get("id").equals(existing.get("paymentId")), 409, "ORDER_STATE_CONFLICT", "支付流水冲突");
      return;
    }
    require(!"succeeded".equals(text(p, "status")), 409, "ORDER_STATE_CONFLICT", "支付单已有不同成功流水");
    Instant paid = Instant.parse(text(fact, "success_time"));
    // 容忍 60 秒时钟偏差，超过则说明时间字段不可信。
    require(!paid.isAfter(Instant.now().plusSeconds(60)), 400, "INVALID_ARGUMENT", "支付时间无效");
    store.create(
        "payment_fact",
        text(o, "ownerId"),
        tx,
        map(
            "paymentId",
            p.get("id"),
            "orderId",
            o.get("id"),
            "paidAt",
            paid.toString(),
            "amount",
            o.get("amount")));
    p.put("status", "succeeded");
    p.put("transactionId", tx);
    store.save(p);
    String previous = text(o, "status");
    o.put("paidAt", paid.toString());
    o.put(
        "pickupDeadlineAt", paid.plusSeconds(number(o, "pickupRetentionHours") * 3600).toString());
    o.put(
        "payment",
        map(
            "paymentId",
            p.get("id"),
            "status",
            "succeeded",
            "paidAmount",
            o.get("amount"),
            "paidAt",
            paid.toString()));
    o.put("status", "paid");
    boolean conflict =
        !List.of("pending_paid", "closing").contains(previous)
            || o.get("cancelReason") != null
            || paid.isAfter(Instant.parse(text(o, "expiresAt")))
            || !text(o, "id").equals(store.occupation(text(o, "petId")));
    if (conflict) {
      store.save(o);
      refund(o, null, number(o, "amount"), "payment_conflict");
    } else {
      String code;
      // 生成不与他人重复的 8 位数字码；单店订单量下循环几乎不会重试，但必须保证唯一。
      do {
        code = auth.digits(8);
      } while (codeExists(code));
      o.put(
          "pickup",
          map(
              "code",
              code,
              "qrPayload",
              auth.randomToken(),
              "expiresAt",
              o.get("pickupDeadlineAt"),
              "buyerConfirmedAt",
              null));
      store.save(o);
    }
  }

  /** 自提码唯一性检查：只与"待自提"的订单比较，已完成订单的码可以回收。 */
  private boolean codeExists(String code) {
    return store.list("order").stream()
        .anyMatch(
            o ->
                "paid".equals(text(o, "status")) && code.equals(text(object(o, "pickup"), "code")));
  }

  /**
   * 创建退款单（只写意图，实际退款由工作器执行）。
   *
   * <p><b>累计口径</b>：把该订单所有退款单的金额求和（含处理中的），加上本次不能超过已付金额。
   * 用"已提交总额"而不是"已成功总额"来卡上限，是为了防止并发提交两笔退款各自校验通过。
   *
   * <p>订单置 {@code refunding} 并清空 {@code pickup}——退款期间自提码立即失效。
   * 首次进入退款时记下 {@code refundPreviousStatus}，部分退款完成后要还原到退款前的状态
   * （而不是一律变成已退款）。
   *
   * @param sale 关联的售后单，可为 null（如支付冲突触发的系统退款）
   */
  public Map<String, Object> refund(
      Map<String, Object> o, Map<String, Object> sale, long amount, String reason) {
    long committed =
        store.list("refund").stream()
            .filter(r -> Objects.equals(o.get("id"), r.get("orderId")))
            .mapToLong(r -> number(r, "amount"))
            .sum();
    require(
        amount > 0 && committed + amount <= number(object(o, "payment"), "paidAmount"),
        422,
        "REFUND_AMOUNT_EXCEEDED",
        "退款总额超出可退金额");
    String no = UUID.randomUUID().toString().replace("-", "");
    Map<String, Object> r =
        store.create(
            "refund",
            text(o, "ownerId"),
            no,
            map(
                "refundNo",
                no,
                "orderId",
                o.get("id"),
                "afterSaleId",
                sale == null ? null : sale.get("id"),
                "reason",
                reason,
                "amount",
                amount,
                "status",
                "processing",
                "succeededAt",
                null,
                "failureReason",
                null,
                "attempted",
                false,
                "providerAccepted",
                false));
    if (!"refunding".equals(text(o, "status"))) o.put("refundPreviousStatus", o.get("status"));
    o.put("status", "refunding");
    o.put("pickup", null);
    store.save(o);
    if (sale != null) {
      sale.put("status", "refunding");
      sale.put("refundId", r.get("id"));
      store.save(sale);
    }
    return r;
  }

  /**
   * 处理退款结果事实。
   *
   * <p>先校验四个字段与本地记录一致（退款单号、商户订单号、退款金额、订单总额、币种），
   * 不一致直接 400，不做任何状态变更。
   *
   * <p>状态处理分三种：
   *
   * <ul>
   *   <li>{@code CLOSED}/{@code ABNORMAL} → 标记 {@code failed} 并写失败原因，等人工重试；
   *   <li>其他非 SUCCESS → 忽略（微信退款是异步的，中间态可能先到）；
   *   <li>SUCCESS → 落 {@code refund_fact}（按 {@code refund_id} 幂等），累加已退金额。
   * </ul>
   *
   * <p>累加时重新从成功退款单求和而不是 {@code += amount}，这样重复回调也不会把金额算重。
   * 全额退完且从未提货时释放库存；部分退款则把订单还原到退款前的状态。
   */
  public void refunded(Map<String, Object> r, Map<String, Object> fact) {
    Map<String, Object> o = store.get("order", text(r, "orderId")), p = payment(o);
    Map<String, Object> amount = object(fact, "amount");
    require(
        text(r, "refundNo").equals(text(fact, "out_refund_no"))
            && text(p, "outTradeNo").equals(text(fact, "out_trade_no"))
            && number(amount, "refund") == number(r, "amount")
            && number(amount, "total") == number(o, "amount")
            && "CNY".equals(text(amount, "currency")),
        400,
        "INVALID_ARGUMENT",
        "退款信息不匹配");
    String status = Objects.toString(fact.get("refund_status"), text(fact, "status"));
    if ("succeeded".equals(text(r, "status"))) return;
    if (!"SUCCESS".equals(status)) {
      if (List.of("CLOSED", "ABNORMAL").contains(status)) {
        r.put("status", "failed");
        r.put("failureReason", "平台退款异常，请核实后重试");
        store.save(r);
      }
      return;
    }
    String tx = text(fact, "refund_id");
    require(tx != null && !tx.isBlank(), 400, "INVALID_ARGUMENT", "退款流水缺失");
    Map<String, Object> old = store.byKey("refund_fact", tx);
    require(
        old == null || r.get("id").equals(old.get("refundId")),
        409,
        "ORDER_STATE_CONFLICT",
        "退款流水冲突");
    if (old == null)
      store.create(
          "refund_fact",
          text(o, "ownerId"),
          tx,
          map(
              "refundId",
              r.get("id"),
              "amount",
              r.get("amount"),
              "succeededAt",
              fact.get("success_time")));
    r.put("status", "succeeded");
    r.put("succeededAt", Objects.toString(fact.get("success_time"), Instant.now().toString()));
    r.put("failureReason", null);
    store.save(r);
    long sum =
        store.list("refund").stream()
            .filter(
                f ->
                    Objects.equals(f.get("orderId"), o.get("id"))
                        && "succeeded".equals(text(f, "status")))
            .mapToLong(f -> number(f, "amount"))
            .sum();
    require(sum <= number(o, "amount"), 422, "REFUND_AMOUNT_EXCEEDED", "退款金额异常");
    o.put("refundedAmount", sum);
    o.put("status", sum == number(o, "amount") ? "refunded" : o.get("refundPreviousStatus"));
    // 只有"没交付过且全额退完"才释放库存；已交付的宠物已归买家，退钱不代表能再卖。
    if (sum == number(o, "amount") && o.get("pickedUpAt") == null) orders.release(o);
    store.save(o);
    if (r.get("afterSaleId") != null) {
      Map<String, Object> s = store.get("after_sale", text(r, "afterSaleId"));
      s.put("status", sum == number(o, "amount") ? "refunded" : "resolved");
      store.save(s);
    }
  }

  /**
   * 构造本地模拟支付事实，仅在 {@code mock-providers=true}（本地/测试）时由
   * {@code LocalPaymentController} 使用；结构与微信查询接口的响应保持一致，
   * 这样 {@link #paid} 无需为模拟数据开后门。
   */
  public Map<String, Object> mockFact(Map<String, Object> p, Map<String, Object> o) {
    return map(
        "out_trade_no",
        p.get("outTradeNo"),
        "trade_state",
        "SUCCESS",
        "transaction_id",
        "local_" + p.get("id"),
        "success_time",
        Instant.now().toString(),
        "amount",
        map("total", o.get("amount"), "payer_total", o.get("amount"), "currency", "CNY"));
  }
}
