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

/** 售后用例：退款、退回和换宠各有独立入口，不混用订单状态。 */
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

  public OperationResult create(String orderId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.create(orders.owned(orderId, actor), body, actor);
    return new OperationResult(201, sales.detail(sale, false), "after_sale", text(sale, "id"));
  }

  public OperationResult detail(String saleId, AuthService.Actor actor) {
    return new OperationResult(200, sales.detail(sales.owned(saleId, actor), false));
  }

  public OperationResult exchangeCode(
      String saleId, Map<String, Object> body, AuthService.Actor actor, String ip) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    new Input(body, "");
    return new OperationResult(
        200, auth.sendSms(actor.phone(), "exchange_confirm", sales.exchangeScope(sale), ip));
  }

  public OperationResult confirmExchange(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, sales.confirmExchange(sales.owned(saleId, actor), body, actor));
  }

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

  public OperationResult adminDetail(String saleId, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    store.audit(actor.id(), "after_sale.read", saleId);
    return new OperationResult(200, sales.detail(sale, true));
  }

  public OperationResult exchange(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(
        200, sales.exchange(sales.owned(saleId, actor), body, actor), "after_sale", saleId);
  }

  public OperationResult review(String saleId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    sales.review(sale, body, actor);
    return changed(sale);
  }

  public OperationResult receiveReturn(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    sales.returned(sale, body, actor);
    return changed(sale);
  }

  public OperationResult deliverExchange(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    sales.deliverExchange(sale, body, actor);
    return changed(sale);
  }

  public OperationResult refundExchange(
      String saleId, Map<String, Object> body, AuthService.Actor actor) {
    Map<String, Object> sale = sales.owned(saleId, actor);
    sales.exchangeRefund(sale, body, actor);
    return changed(sale);
  }

  private OperationResult changed(Map<String, Object> sale) {
    return new OperationResult(
        "refunding".equals(text(sale, "status")) ? 202 : 200,
        sales.detail(sale, true),
        "after_sale",
        text(sale, "id"));
  }
}
