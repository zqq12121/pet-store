package com.warmpaw.application;

import static com.warmpaw.common.Json.*;
import static com.warmpaw.common.TimeRange.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AfterSaleService;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogReader;
import com.warmpaw.service.OrderService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 售后用例编排层。
 *
 * <p><b>每个动作一个独立入口</b>：申请、审核、验收退回、选替换宠、确认换宠、交付换宠、改退款，
 * 七个方法对应七个 HTTP 端点，而不是一个 {@code action} 参数走 switch。
 * 这样每个动作的字段白名单、幂等键、权限要求和状态前置条件都能各自精确表达，
 * 也避免"参数拼错落到默认分支"这类问题。
 *
 * <p><b>退款中的响应码是 202</b>：{@link #changed} 会检查售后单是否进入 {@code refunding}。
 * 一旦发起退款，实际资金动作是异步的（见 {@code PaymentWorker}），所以给客户端"已接受、处理中"，
 * 而不是 200 让人以为已经退到账。
 *
 * <p>事务同样是 {@code MANDATORY}，由外层执行器统一开启并持锁。
 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class AfterSaleApplicationService {
  private final BusinessRepository store;
  private final OrderService orders;
  private final AfterSaleService sales;
  private final AuthService auth;

  public AfterSaleApplicationService(
      BusinessRepository store, OrderService orders, AfterSaleService sales, AuthService auth) {
    this.store = store;
    this.orders = orders;
    this.sales = sales;
    this.auth = auth;
  }

  /** 某订单的售后记录列表：先校验订单归属，再只返回概要字段（不含材料与时间线）。 */
  public OperationResult listForOrder(
      String orderId, Map<String, Object> query, AuthService.Actor actor) {
    Map<String, Object> order = orders.owned(orderId, actor);
    return new OperationResult(
        200,
        CatalogReader.page(
            orders.sales(order).stream()
                .map(
                    item ->
                        CatalogReader.select(
                            item,
                            "id,orderId,type,status,requestedResolution,approvedResolution,createdAt,updatedAt"))
                .toList(),
            query));
  }

  /** 买家提交售后申请；订单归属校验在前，业务校验在领域层。 */
  public OperationResult create(String orderId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.create(orders.owned(orderId, actor), body, actor);
    return new OperationResult(201, sales.detail(sale, false), "after_sale", text(sale, "id"));
  }

  /** 买家查看自己的售后单详情。 */
  public OperationResult detail(String saleId, AuthService.Actor actor) {
    return new OperationResult(200, sales.detail(sales.owned(saleId, actor), false));
  }

  /**
   * 发送换宠确认短信。
   *
   * <p>注意收件人用的是 {@code actor.phone()}（登录账号绑定手机号），不是订单联系号码——
   * 换宠是针对已注册买家的操作，必须发到账号本人手里，避免被手填号码劫持。
   *
   * <p>{@code scope} 由领域层计算（含售后单/换宠/替换宠三元组），保证验证码与具体替换宠绑定。
   */
  public OperationResult exchangeCode(
      String saleId, Map<String, Object> body, AuthService.Actor actor, String ip) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    new Input(body, "");
    return new OperationResult(
        200, auth.sendSms(actor.phone(), "exchange_confirm", sales.exchangeScope(sale), ip));
  }

  /** 买家确认替换宠。 */
  public OperationResult confirmExchange(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, sales.confirmExchange(sales.owned(saleId, actor), body, actor));
  }

  /**
   * 后台售后列表。
   *
   * <p>支持按状态、订单号、创建时间区间筛选。按订单号筛选时需要回查订单取 {@code orderNo}，
   * 所以是 O(n) 的双层查找——单店数据量下可接受，若要扩展应改为先按订单号查订单再筛售后。
   */
  public OperationResult adminList(Map<String, Object> q) {
    new Input(q, "status,orderNo,from,to,page,pageSize");
    validateRange(q, "from", "to");
    return new OperationResult(
        200,
        CatalogReader.page(
            store.list("after_sale").stream()
                .filter(
                    s ->
                        !q.containsKey("status")
                            || Objects.equals(q.get("status"), s.get("status")))
                .filter(
                    s ->
                        !q.containsKey("orderNo")
                            || text(store.get("order", text(s, "orderId")), "orderNo")
                                .contains(text(q, "orderNo")))
                .filter(s -> inRange(s, "createdAt", q, "from", "to"))
                .map(
                    s ->
                        CatalogReader.select(
                            s,
                            "id,orderId,type,status,requestedResolution,approvedResolution,createdAt,updatedAt"))
                .toList(),
            q));
  }

  /**
   * 后台售后详情：内联完整订单，并写 {@code after_sale.read} 审计——
   * 这里能看到诊断材料与买家个人信息，属于敏感读。
   */
  public OperationResult adminDetail(String saleId, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    store.audit(actor.id(), "after_sale.read", saleId);
    return new OperationResult(200, sales.detail(sale, true));
  }

  /** 店长为换宠选择替换宠物（同价、未退款、可售）。 */
  public OperationResult exchange(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(
        200, sales.exchange(sales.owned(saleId, actor), body, actor), "after_sale", saleId);
  }

  /** 店长审核售后申请（通过/驳回）。 */
  public OperationResult review(String saleId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    sales.review(sale, body, actor);
    return changed(sale);
  }

  /** 门店验收买家退回的原宠。 */
  public OperationResult receiveReturn(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    sales.returned(sale, body, actor);
    return changed(sale);
  }

  /** 交付替换宠，结案。 */
  public OperationResult deliverExchange(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    sales.deliverExchange(sale, body, actor);
    return changed(sale);
  }

  /** 换宠协商破裂，转为全额退款（需买家书面同意材料）。 */
  public OperationResult refundExchange(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    sales.exchangeRefund(sale, body, actor);
    return changed(sale);
  }

  /**
   * 统一的"动作已完成"响应：处在 {@code refunding} 时返回 202（资金异步处理中），
   * 其余返回 200。
   *
   * <p>响应体一律用管理层详情（{@code admin=true}），因为这些动作只有店长能触发。
   */
  private OperationResult changed(Map<String, Object> sale) {
    return new OperationResult(
        "refunding".equals(text(sale, "status")) ? 202 : 200,
        sales.detail(sale, true),
        "after_sale",
        text(sale, "id"));
  }
}
