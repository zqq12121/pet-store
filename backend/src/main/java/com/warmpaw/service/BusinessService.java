package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HTTP 契约编排层：事务、角色、幂等统一处理，具体规则由各业务 Service 执行。 */
@Service
public class BusinessService {
  public record Reply(int status, Object data, String kind, String resource) {
    public Reply(int status, Object data) {
      this(status, data, null, null);
    }
  }

  private final Store store;
  private final CatalogService catalog;
  private final OrderService orders;
  private final PaymentService payments;
  private final AfterSaleService sales;
  private final AuthService auth;
  private final TemporaryStore temp;

  public BusinessService(
      Store store,
      CatalogService catalog,
      OrderService orders,
      PaymentService payments,
      AfterSaleService sales,
      AuthService auth,
      TemporaryStore temp) {
    this.store = store;
    this.catalog = catalog;
    this.orders = orders;
    this.payments = payments;
    this.sales = sales;
    this.auth = auth;
    this.temp = temp;
  }

  @Transactional
  public Reply execute(
      String method,
      String path,
      Map<String, Object> body,
      Map<String, Object> query,
      String key,
      AuthService.Actor actor,
      String ip) {
    boolean admin = path.startsWith("/admin/"), write = !method.equals("GET");
    if (admin) AuthService.role(actor, "admin");
    else if (path.equals("/analytics/events")) {
      require(
          actor != null && List.of("buyer", "guest").contains(actor.role()),
          401,
          "UNAUTHORIZED",
          "缺少访问身份");
    } else if (!isPublic(path)) AuthService.role(actor, "buyer");
    if (write) store.mapper.lock();
    boolean idem = write && requiresKey(path);
    String scope = null;
    if (idem) {
      require(
          key != null
              && key.matches(
                  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
          400,
          "VALIDATION_ERROR",
          "请提供 UUID 格式的 Idempotency-Key");
      scope = hash(actor.id() + "\n" + method + "\n" + path + "\n" + key);
      Map<String, Object> old = store.mapper.replay(scope);
      if (old != null) {
        require(
            hash(canonical(body)).equals(old.get("body_hash")),
            409,
            "IDEMPOTENCY_CONFLICT",
            "同一幂等键的请求内容不同");
        return replay(
            read(old.get("response_body").toString()),
            path,
            admin,
            ((Number) old.get("http_status")).intValue());
      }
    }
    Reply reply = route(method, path, body, query, actor, ip);
    if (idem)
      store.mapper.remember(
          scope,
          hash(canonical(body)),
          reply.resource(),
          com.warmpaw.common.Json.write(
              map("data", reply.data(), "kind", reply.kind(), "resource", reply.resource())),
          reply.status(),
          Instant.now().toString());
    return reply;
  }

  private boolean isPublic(String path) {
    return path.equals("/shop")
        || path.equals("/home")
        || path.equals("/pet-categories")
        || path.equals("/pets")
        || path.startsWith("/pets/")
        || path.equals("/agreements/current")
        || path.equals("/payment-capabilities");
  }

  private boolean requiresKey(String path) {
    return (path.equals("/orders")
            || path.startsWith("/orders/")
            || path.matches("/admin/orders/[^/]+/pickup")
            || path.matches(
                "/admin/after-sales/[^/]+/(review|returns|exchange|exchange-delivery|exchange-refund)")
            || path.matches("/after-sales/[^/]+/exchange-confirmations")
            || path.matches("/admin/refunds/[^/]+/retry"))
        && !path.endsWith("verification-codes")
        && !path.equals("/orders/preview");
  }

  private Reply replay(Map<String, Object> saved, String path, boolean admin, int status) {
    String kind = text(saved, "kind"), id = text(saved, "resource");
    if (kind == null || id == null) return new Reply(status, saved.get("data"));
    Map<String, Object> entity = store.get(kind, id);
    if (kind.equals("order")) {
      if (path.endsWith("/cancel")) return new Reply(202, cancelResult(entity));
      if (path.endsWith("/pickup")) return new Reply(200, saved.get("data"));
      return new Reply(200, orders.detail(entity, admin));
    }
    if (kind.equals("payment")) return new Reply(200, payments.view(entity));
    if (kind.equals("after_sale"))
      return new Reply(
          200, path.endsWith("/exchange") ? entity.get("exchange") : sales.detail(entity, admin));
    if (kind.equals("refund")) return new Reply(202, refundDto(entity));
    return new Reply(status, saved.get("data"));
  }

  private Map<String, Object> cancelResult(Map<String, Object> o) {
    return map(
        "orderId",
        o.get("id"),
        "status",
        o.get("status"),
        "nextPollAfter",
        List.of("closing", "refunding").contains(text(o, "status")) ? 2 : 0);
  }

  private Map<String, Object> refundDto(Map<String, Object> r) {
    return CatalogService.select(
        r, "id,refundNo,afterSaleId,reason,amount,status,createdAt,succeededAt,failureReason");
  }

  private Reply route(
      String method,
      String path,
      Map<String, Object> b,
      Map<String, Object> q,
      AuthService.Actor a,
      String ip) {
    String[] parts = path.substring(1).split("/");
    String id = parts.length > 1 ? parts[1] : "";
    boolean get = method.equals("GET");
    if (get && path.equals("/home")) {
      new Input(q, "");
      return new Reply(200, catalog.home());
    }
    if (get && path.equals("/shop")) return new Reply(200, catalog.publicShop());
    if (get && path.equals("/pet-categories")) return new Reply(200, catalog.categories());
    if (get && path.equals("/pets")) return new Reply(200, catalog.pets(q, false));
    if (get && path.matches("/pets/[^/]+")) return new Reply(200, catalog.publicPet(id));
    if (get && path.equals("/agreements/current"))
      return new Reply(
          200,
          agreementDto(
              catalog.currentAgreement(
                  new Input(q, "type").choice("type", "live_pet_trade,pickup_confirmation"))));
    if (get && path.equals("/payment-capabilities")) return new Reply(200, payments.capabilities());
    if (get && path.equals("/me")) return new Reply(200, auth.profile(a));
    if (method.equals("PATCH") && path.equals("/me")) {
      Input in = new Input(b, "nickname,avatarFileId");
      require(!b.isEmpty(), 400, "VALIDATION_ERROR", "至少修改一个字段");
      Map<String, Object> u = store.get("user", a.id());
      if (in.has("nickname")) u.put("nickname", in.str("nickname", 1, 30));
      if (in.has("avatarFileId"))
        u.put(
            "avatarUrl",
            b.get("avatarFileId") == null
                ? null
                : catalog
                    .asset(in.str("avatarFileId", 1, 64), "avatar", a.id(), null)
                    .get("publicUrl"));
      store.save(u);
      return new Reply(200, auth.profile(a));
    }
    if (method.equals("POST") && path.equals("/orders/preview"))
      return new Reply(200, orders.preview(b));
    if (method.equals("POST") && path.equals("/orders")) {
      Map<String, Object> o = orders.create(b, a);
      return new Reply(201, orders.detail(o, false), "order", text(o, "id"));
    }
    if (get && path.equals("/orders")) return new Reply(200, listOrders(q, a, false));
    if (path.matches("/orders/[^/]+(/[^/]+)?") && !id.equals("preview")) {
      Map<String, Object> o = orders.owned(id, a);
      String action = parts.length > 2 ? parts[2] : "";
      if (get && action.isEmpty()) return new Reply(200, orders.detail(o, false));
      if (get && action.equals("payment")) return new Reply(200, payments.status(o));
      if (get && action.equals("after-sales"))
        return new Reply(
            200,
            CatalogService.page(
                orders.sales(o).stream()
                    .map(
                        s ->
                            CatalogService.select(
                                s,
                                "id,orderId,type,status,requestedResolution,approvedResolution,createdAt,updatedAt"))
                    .toList(),
                q));
      if (method.equals("POST"))
        switch (action) {
          case "cancel" -> {
            new Input(b, "reason").optional("reason", 200);
            payments.cancel(o, "user_cancelled");
            return new Reply(202, cancelResult(o), "order", id);
          }
          case "payments" -> {
            Map<String, Object> p = payments.begin(o, b);
            return new Reply(201, payments.view(p), "payment", text(p, "id"));
          }
          case "pickup-verification-codes" -> {
            new Input(b, "");
            orders.pickupAllowed(o);
            return new Reply(200, auth.sendSms(text(o, "contactPhone"), "pickup_confirm", id, ip));
          }
          case "pickup-confirmations" -> {
            return new Reply(200, orders.confirm(o, b, a));
          }
          case "after-sales" -> {
            Map<String, Object> s = sales.create(o, b, a);
            return new Reply(201, sales.detail(s, false), "after_sale", text(s, "id"));
          }
          default -> {}
        }
    }
    if (path.matches("/after-sales/[^/]+(/[^/]+)?")) {
      Map<String, Object> s = sales.owned(id, a);
      String action = parts.length > 2 ? parts[2] : "";
      if (get && action.isEmpty()) return new Reply(200, sales.detail(s, false));
      if (method.equals("POST") && action.equals("exchange-verification-codes")) {
        new Input(b, "");
        return new Reply(
            200, auth.sendSms(a.phone(), "exchange_confirm", sales.exchangeScope(s), ip));
      }
      if (method.equals("POST") && action.equals("exchange-confirmations"))
        return new Reply(200, sales.confirmExchange(s, b, a));
    }
    if (path.startsWith("/admin/")) return admin(method, path, b, q, a);
    if (method.equals("POST") && path.equals("/analytics/events")) return analytics(b, a);
    if (auth.local() && method.equals("POST") && path.matches("/(dev|demo)/orders/[^/]+/pay")) {
      new Input(b, "");
      Map<String, Object> o = orders.owned(parts[2], a);
      Store.state(o, "pending_paid");
      require(
          !Instant.now().isAfter(Instant.parse(text(o, "expiresAt"))),
          409,
          "ORDER_EXPIRED",
          "订单已过期");
      Map<String, Object> p = payments.begin(o, map("scene", "h5"));
      payments.paid(p, payments.mockFact(p, o));
      return new Reply(200, orders.detail(store.get("order", text(o, "id")), false));
    }
    throw new ApiException(404, "RESOURCE_NOT_FOUND", "接口不存在");
  }

  private Map<String, Object> agreementDto(Map<String, Object> a) {
    return CatalogService.select(
        a,
        "id,type,version,title,content,contentFormat,contentHash,publishedAt,healthGuaranteeDays,status,createdAt");
  }

  private Reply admin(
      String method,
      String path,
      Map<String, Object> b,
      Map<String, Object> q,
      AuthService.Actor a) {
    boolean get = method.equals("GET");
    String[] parts = path.substring(1).split("/");
    String id = parts.length > 2 ? parts[2] : "";
    if (get && path.equals("/admin/shop"))
      return new Reply(
          200,
          CatalogService.select(
              catalog.shop(),
              "id,name,address,latitude,longitude,coordinateSystem,phone,wechat,businessHours,pickupInstructions,version,banners,paymentTimeoutMinutes,pickupRetentionHours,exchangeEnabled"));
    if (method.equals("PUT") && path.equals("/admin/shop")) {
      catalog.updateShop(b, a.id());
      return admin("GET", path, map(), map(), a);
    }
    if (path.equals("/admin/pets")) {
      if (get) return new Reply(200, catalog.pets(q, true));
      if (method.equals("POST")) return new Reply(201, catalog.writePet(null, b, a.id()));
    }
    if (path.matches("/admin/pets/[^/]+(/[^/]+)?")) {
      if (parts.length == 3) {
        if (get) return new Reply(200, catalog.adminPet(store.get("pet", id)));
        if (method.equals("PUT")) return new Reply(200, catalog.writePet(id, b, a.id()));
      } else if (method.equals("POST") && List.of("publish", "unpublish").contains(parts[3]))
        return new Reply(200, catalog.publish(id, b, a.id(), parts[3].equals("publish")));
    }
    if (path.equals("/admin/agreements")) {
      if (get) {
        new Input(q, "type,page,pageSize");
        return new Reply(
            200,
            CatalogService.page(
                store.list("agreement").stream()
                    .filter(
                        x -> !q.containsKey("type") || Objects.equals(x.get("type"), q.get("type")))
                    .map(this::agreementDto)
                    .toList(),
                q));
      }
      if (method.equals("POST"))
        return new Reply(201, agreementDto(catalog.createAgreement(b, a.id())));
    }
    if (get && path.matches("/admin/agreements/[^/]+"))
      return new Reply(200, agreementDto(store.get("agreement", id)));
    if (method.equals("POST") && path.matches("/admin/agreements/[^/]+/publish"))
      return new Reply(200, agreementDto(catalog.publishAgreement(id, b, a.id())));
    if (get && path.equals("/admin/orders")) return new Reply(200, listOrders(q, a, true));
    if (get && path.matches("/admin/orders/[^/]+")) {
      store.audit(a.id(), "order.read", id);
      return new Reply(200, orders.detail(store.get("order", id), true));
    }
    if (method.equals("POST") && path.equals("/admin/pickups/lookup")) {
      temp.checkFailures("pickup-lookup:" + a.id(), 5);
      try {
        Reply reply = new Reply(200, orders.lookup(b));
        temp.resetFailures("pickup-lookup:" + a.id());
        return reply;
      } catch (ApiException e) {
        if (e.status == 404) temp.failed("pickup-lookup:" + a.id(), 60);
        throw e;
      }
    }
    if (method.equals("POST") && path.matches("/admin/orders/[^/]+/pickup"))
      return new Reply(200, orders.pickup(store.get("order", id), b, a), "order", id);
    if (get && path.equals("/admin/after-sales")) {
      new Input(q, "status,orderNo,from,to,page,pageSize");
      validateRange(q, "from", "to");
      return new Reply(
          200,
          CatalogService.page(
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
                          CatalogService.select(
                              s,
                              "id,orderId,type,status,requestedResolution,approvedResolution,createdAt,updatedAt"))
                  .toList(),
              q));
    }
    if (path.matches("/admin/after-sales/[^/]+(/[^/]+)?")) {
      Map<String, Object> s = sales.owned(id, a);
      if (get && parts.length == 3) {
        store.audit(a.id(), "after_sale.read", id);
        return new Reply(200, sales.detail(s, true));
      }
      if (parts.length == 4) {
        String action = parts[3];
        if (method.equals("PUT") && action.equals("exchange"))
          return new Reply(200, sales.exchange(s, b, a), "after_sale", id);
        if (method.equals("POST")) {
          switch (action) {
            case "review" -> sales.review(s, b, a);
            case "returns" -> sales.returned(s, b, a);
            case "exchange-delivery" -> sales.deliverExchange(s, b, a);
            case "exchange-refund" -> sales.exchangeRefund(s, b, a);
            default -> throw new ApiException(404, "RESOURCE_NOT_FOUND", "接口不存在");
          }
          return new Reply(
              "refunding".equals(text(s, "status")) ? 202 : 200,
              sales.detail(s, true),
              "after_sale",
              id);
        }
      }
    }
    if (method.equals("POST") && path.matches("/admin/refunds/[^/]+/retry")) {
      new Input(b, "reason").str("reason", 1, 500);
      Map<String, Object> r = store.get("refund", id);
      Store.state(r, "failed");
      require(auth.local(), 409, "REFUND_IN_PROGRESS", "需先查询原退款并确认平台允许重试，不能盲目重发");
      r.put("status", "processing");
      store.save(r);
      return new Reply(202, refundDto(r), "refund", id);
    }
    if (get && path.equals("/admin/dashboard")) return new Reply(200, dashboard());
    throw new ApiException(404, "RESOURCE_NOT_FOUND", "接口不存在");
  }

  private Map<String, Object> listOrders(
      Map<String, Object> q, AuthService.Actor a, boolean admin) {
    new Input(
        q,
        admin
            ? "orderNo,status,contactPhone,petId,createdFrom,createdTo,page,pageSize"
            : "tab,page,pageSize");
    validateRange(q, "createdFrom", "createdTo");
    String tab = Objects.toString(q.get("tab"), "all");
    require(
        List.of("all", "pending_paid", "pending_pickup", "completed", "after_sale").contains(tab),
        400,
        "VALIDATION_ERROR",
        "订单标签不正确");
    if (q.containsKey("contactPhone")) {
      new Input(q, "orderNo,status,contactPhone,petId,createdFrom,createdTo,page,pageSize")
          .phone("contactPhone");
      store.audit(a.id(), "order.phone_search", null);
    }
    return CatalogService.page(
        store.list("order").stream()
            .filter(o -> admin || a.id().equals(text(o, "ownerId")))
            .filter(
                o ->
                    List.of("status", "contactPhone", "petId").stream()
                        .allMatch(k -> !q.containsKey(k) || Objects.equals(q.get(k), o.get(k))))
            .filter(
                o -> !q.containsKey("orderNo") || text(o, "orderNo").contains(text(q, "orderNo")))
            .filter(o -> inRange(o, "createdAt", q, "createdFrom", "createdTo"))
            .filter(
                o ->
                    switch (tab) {
                      case "pending_paid" ->
                          List.of("pending_paid", "closing").contains(text(o, "status"));
                      case "pending_pickup" -> "paid".equals(text(o, "status"));
                      case "completed" -> "completed".equals(text(o, "status"));
                      case "after_sale" ->
                          !orders.sales(o).isEmpty()
                              || number(o, "refundedAmount") > 0
                              || "refunding".equals(text(o, "status"));
                      default -> true;
                    })
            .map(orders::summary)
            .toList(),
        q);
  }

  public static void validateRange(Map<String, Object> q, String from, String to) {
    Instant start = q.get(from) == null ? null : AfterSaleService.parseInstant(text(q, from)),
        end = q.get(to) == null ? null : AfterSaleService.parseInstant(text(q, to));
    if (start != null && end != null)
      require(
          !start.isAfter(end) && Duration.between(start, end).toDays() <= 366,
          400,
          "VALIDATION_ERROR",
          "时间范围须按先后顺序且不超过366天");
  }

  private static boolean inRange(
      Map<String, Object> row, String field, Map<String, Object> q, String from, String to) {
    Instant value = Instant.parse(text(row, field));
    return (q.get(from) == null || !value.isBefore(AfterSaleService.parseInstant(text(q, from))))
        && (q.get(to) == null || !value.isAfter(AfterSaleService.parseInstant(text(q, to))));
  }

  private Map<String, Object> dashboard() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    long
        gross =
            store.list("payment_fact").stream()
                .filter(p -> day(p, "paidAt", today))
                .mapToLong(p -> number(p, "amount"))
                .sum(),
        refund =
            store.list("refund").stream()
                .filter(r -> "succeeded".equals(text(r, "status")) && day(r, "succeededAt", today))
                .mapToLong(r -> number(r, "amount"))
                .sum();
    return map(
        "date",
        today.toString(),
        "createdOrderCount",
        store.list("order").stream().filter(o -> day(o, "createdAt", today)).count(),
        "paidOrderCount",
        store.list("payment_fact").stream().filter(p -> day(p, "paidAt", today)).count(),
        "grossSalesAmount",
        gross,
        "refundAmount",
        refund,
        "netCashAmount",
        gross - refund,
        "onSalePetCount",
        store.list("pet").stream().filter(p -> "on_sale".equals(text(p, "status"))).count(),
        "aiSessionCount",
        0,
        "pendingTasks",
        map(
            "pickupCount",
            store.list("order").stream().filter(o -> "paid".equals(text(o, "status"))).count(),
            "afterSaleReviewCount",
            store.list("after_sale").stream()
                .filter(s -> "pending_review".equals(text(s, "status")))
                .count(),
            "failedRefundCount",
            store.list("refund").stream().filter(r -> "failed".equals(text(r, "status"))).count(),
            "knowledgeFailedJobCount",
            0,
            "paymentExceptionCount",
            store.list("payment").stream().filter(p -> p.get("lastProviderError") != null).count()),
        "updatedAt",
        Instant.now().toString());
  }

  private boolean day(Map<String, Object> m, String key, LocalDate date) {
    return m.get(key) != null
        && Instant.parse(text(m, key))
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .equals(date);
  }

  private Reply analytics(Map<String, Object> b, AuthService.Actor a) {
    Input in = new Input(b, "events");
    require(
        b.get("events") instanceof List<?>
            && !objects(b, "events").isEmpty()
            && objects(b, "events").size() <= 20
            && com.warmpaw.common.Json.write(b)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length
                <= 32768,
        400,
        "VALIDATION_ERROR",
        "事件批次不正确");
    int accepted = 0, duplicate = 0;
    for (Map<String, Object> e : objects(b, "events")) {
      Input ei = new Input(e, "eventId,eventType,occurredAt,pagePath,petId,properties");
      String event = ei.str("eventId", 36, 36);
      try {
        UUID.fromString(event);
      } catch (Exception ex) {
        throw new ApiException(400, "VALIDATION_ERROR", "eventId必须为UUID");
      }
      String type = ei.choice("eventType", "page_view,pet_detail_view,search"),
          path = ei.str("pagePath", 1, 200);
      PaymentService.safePath(path);
      require(
          !path.contains("?") && !path.contains("#") && !path.contains("orders"),
          400,
          "VALIDATION_ERROR",
          "页面路径不能包含敏感参数");
      Instant at = AfterSaleService.parseInstant(ei.str("occurredAt", 1, 40));
      require(
          Math.abs(Duration.between(at, Instant.now()).toSeconds()) <= 86400,
          400,
          "VALIDATION_ERROR",
          "事件时间超出范围");
      Map<String, Object> props = object(e, "properties");
      Input pi =
          new Input(
              props,
              type.equals("search")
                  ? "keyword"
                  : type.equals("page_view") ? "page,pathDepth" : "pathDepth");
      if (type.equals("search")) pi.str("keyword", 1, 50);
      else {
        pi.integer("pathDepth", 0, 20);
        if (type.equals("page_view")) pi.choice("page", "home,list,detail,other");
      }
      if (type.equals("pet_detail_view")) catalog.publicPet(ei.str("petId", 1, 64));
      if (store.byKey("event", a.id() + ":" + event) != null) {
        duplicate++;
        continue;
      }
      store.create("event", a.id(), a.id() + ":" + event, copy(e));
      accepted++;
    }
    return new Reply(202, map("acceptedCount", accepted, "duplicateCount", duplicate));
  }
}
