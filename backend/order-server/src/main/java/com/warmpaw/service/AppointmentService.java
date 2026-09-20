package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;
import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 预约复用单宠订单和库存锁；所有写操作由调用方在同一数据库事务内加锁。 */
@Service
public class AppointmentService {
  private final BusinessRepository store;
  private final OrderService orders;
  private final CatalogReader catalog;
  private final AppointmentSmsNotifier notifications;

  public AppointmentService(BusinessRepository store, OrderService orders, CatalogReader catalog, AppointmentSmsNotifier notifications) {
    this.store = store; this.orders = orders; this.catalog = catalog;
    this.notifications = notifications;
  }

  public Map<String, Object> create(Map<String, Object> body, AuthService.Actor actor) {
    Instant now = Instant.now();
    String value = new Input(body,
        "petId,productVersion,expectedAmount,contactName,contactPhone,remark,agreementVersion,agreementContentHash,agreementAccepted,visitAt")
        .str("visitAt", 1, 40);
    Instant visit = AppointmentHours.validateVisit(value, text(catalog.shop(), "businessHours"), now);
    expire(now);
    require(store.list("order").stream().noneMatch(o -> actor.id().equals(text(o, "ownerId")) && active(o)),
        409, "ACTIVE_APPOINTMENT_EXISTS", "你已有有效预约，请先取消或完成后再预约");
    // 内部复用原订单快照和一宠一锁，不创建微信支付单；事务提交前切换为预约状态。
    Map<String, Object> orderBody = new LinkedHashMap<>(body);
    orderBody.remove("visitAt");
    Map<String, Object> order = orders.create(orderBody, actor);
    Instant deadline = now.plus(Duration.ofHours(24));
    if (visit.isBefore(deadline)) deadline = visit;
    order.put("status", "pending_confirmation");
    order.put("expiresAt", deadline.toString());
    order.put("appointment", map("visitAt", visit.toString(), "confirmationDeadlineAt", deadline.toString()));
    order.put("payment", map("status", "offline_unpaid", "paidAmount", 0));
    store.save(order);
    store.audit(actor.id(), "appointment.create", text(order, "id"));
    notifications.notifyAfterCommit(order, "submitted");
    return order;
  }

  private boolean active(Map<String, Object> order) {
    return List.of("pending_confirmation", "reservation_confirmed").contains(text(order, "status"));
  }

  public void cancel(Map<String, Object> order, String reason, String actor) {
    BusinessRepository.state(order, "pending_confirmation", "reservation_confirmed");
    order.put("status", "cancelled");
    order.put("cancelReason", reason);
    order.put("cancelledAt", Instant.now().toString());
    orders.release(order);
    store.save(order);
    store.audit(actor, "appointment.cancel", text(order, "id"));
  }

  public void process(Map<String, Object> order, String action, Map<String, Object> body, AuthService.Actor actor) {
    require(order.containsKey("appointment"), 409, "ORDER_STATE_CONFLICT", "该记录不是到店预约");
    Input input = new Input(body, "reason,paymentReceived,deliveryConfirmed,quarantineVerified");
    require(List.of("confirm", "cancel", "complete").contains(action), 404, "RESOURCE_NOT_FOUND", "预约操作不存在");
    if (action.equals("cancel")) {
      cancel(order, input.str("reason", 1, 200), actor.id());
      notifications.notifyAfterCommit(order, "cancelled");
      return;
    }
    require(active(order) && Instant.parse(text(order, "expiresAt")).isAfter(Instant.now()),
        409, "APPOINTMENT_EXPIRED", "预约已过期或状态已变化，请刷新");
    Map<String, Object> appointment = object(order, "appointment");
    if (action.equals("confirm")) {
      BusinessRepository.state(order, "pending_confirmation");
      appointment.put("confirmedAt", Instant.now().toString());
      order.put("status", "reservation_confirmed");
      order.put("expiresAt", Instant.parse(text(appointment, "visitAt")).plus(Duration.ofHours(2)).toString());
    } else {
      BusinessRepository.state(order, "reservation_confirmed");
      input.yes("paymentReceived");
      input.yes("deliveryConfirmed");
      input.yes("quarantineVerified");
      Map<String, Object> pet = store.get("pet", text(order, "petId"));
      require(text(order, "id").equals(store.occupation(text(pet, "id"))) && "reserved".equals(text(pet, "status")),
          409, "ORDER_STATE_CONFLICT", "预约库存占用不一致");
      var quarantine = orders.deliveryQuarantine(pet, Map.of(), actor.id());
      String now = Instant.now().toString();
      // 线下收款独立标识，不能伪造微信流水，也不能进入自动原路退款。
      order.put("payment", map("status", "offline_received", "paidAmount", order.get("amount"), "paidAt", now));
      order.put("paidAt", now);
      order.put("pickedUpAt", now);
      order.put("completedAt", now);
      order.put("deliveryQuarantine", catalog.publicQuarantine(quarantine));
      order.put("deliveryOriginalFileIds", strings(quarantine, "originalFileIds"));
      order.put("status", "completed");
      appointment.put("completedBy", actor.id());
      pet.put("status", "sold");
      pet.put("activeOrderId", null);
      store.release(text(pet, "id"), text(order, "id"));
      store.save(pet);
    }
    store.save(order);
    store.audit(actor.id(), "appointment." + action, text(order, "id"));
    notifications.notifyAfterCommit(order, action.equals("confirm") ? "confirmed" : "completed");
  }

  /** 过期只释放此预约自己的库存；由一分钟任务执行，创建预约时也清理过期占用。 */
  public void expire(Instant now) {
    for (Map<String, Object> order : store.list("order")) {
      if (active(order) && !Instant.parse(text(order, "expiresAt")).isAfter(now)) {
        order.put("status", "expired");
        order.put("closedAt", now.toString());
        order.put("cancelReason", "appointment_timeout");
        orders.release(order);
        store.save(order);
        notifications.notifyAfterCommit(order, "expired");
      }
    }
  }
}
