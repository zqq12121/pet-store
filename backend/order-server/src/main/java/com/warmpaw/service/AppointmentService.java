package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;
import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 到店预约业务：把"买一只宠物"改写成"预约到店看一只宠物"。
 *
 * <p>与线上商城订单的区别：
 *
 * <ul>
 *   <li>不创建微信支付单，付款在到店交付时线下完成，订单里记为 {@code offline_unpaid}；
 *   <li>状态机独立：{@code pending_confirmation}（待店长确认）→ {@code reservation_confirmed}（已确认待到店）
 *       → {@code completed}（已交付）；任一步可转 {@code cancelled}，超时转 {@code expired}；
 *   <li>库存锁复用订单的"一宠一锁"（{@code pet.active_order_id}），不另建预约库存表。
 * </ul>
 *
 * <p><b>事务边界</b>：本类写方法都假定调用方已开启事务。{@link #create} 例外地不持有全局写锁，
 * 而是由 HTTP 层在事务外加 Redis 分布式锁（用户 + 宠物），宠物状态用乐观锁条件更新；
 * 其余写方法仍持有全局写锁（{@code BusinessRepository.lock()}）。这里不标注 {@code @Transactional}，是因为状态流转要和
 * 订单创建、库存占用处在同一个事务里，由 {@code OrderApplicationService} 统一控制。
 */
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

  /**
   * 创建预约。
   *
   * <p>先做三件事再落单：校验到店时间是否落在营业时段内、清理已过期的历史预约、确认该用户没有进行中的预约。
   * 最后由 {@link OrderService#createAppointment} 复用订单快照与库存锁生成订单主体，再改写成预约状态。
   *
   * <p>确认截止时间是 {@code min(创建时刻 + 24h, 到店时刻)}——避免出现"到店时间已过、预约还没确认"的窗口。
   *
   * @param body 预约请求体，字段白名单见下方 {@link Input} 构造；{@code visitAt} 为 ISO-8601 到店时刻
   * @param actor 当前登录买家，一个买家同时只允许一笔进行中的预约
   * @return 落库后的订单视图，含 {@code appointment} 与 {@code payment} 两个子对象
   */
  public Map<String, Object> create(Map<String, Object> body, AuthService.Actor actor) {
    Instant now = Instant.now();
    String value = new Input(body,
        "petId,productVersion,expectedAmount,contactName,contactPhone,remark,agreementVersion,agreementContentHash,agreementAccepted,visitAt")
        .str("visitAt", 1, 40);
    Instant visit = AppointmentHours.validateVisit(value, text(catalog.shop(), "businessHours"), now);
    // 预约下单不持有全局锁，所以这里不再顺手调用 expire(now) 清理别人的过期预约
    // （那是对其他订单的写，会和持全局锁的后台操作竞争）。过期清理只由定时任务做；
    // 这里把"已过截止时间但还没被清理"的预约视为无效，不阻止用户重新预约。
    // 并发安全：同一用户的预约请求已被事务外的用户维度分布式锁串行化。
    require(store.list("order").stream().noneMatch(o -> actor.id().equals(text(o, "ownerId")) && active(o)
            && Instant.parse(text(o, "expiresAt")).isAfter(now)),
        409, "ACTIVE_APPOINTMENT_EXISTS", "你已有有效预约，请先取消或完成后再预约");
    // 内部复用原订单快照和一宠一锁，不创建微信支付单；事务提交前切换为预约状态。
    Map<String, Object> orderBody = new LinkedHashMap<>(body);
    orderBody.remove("visitAt");
    Map<String, Object> order = orders.createAppointment(orderBody, actor);
    Instant deadline = now.plus(Duration.ofHours(24));
    if (visit.isBefore(deadline)) deadline = visit;
    order.put("status", "pending_confirmation");
    order.put("expiresAt", deadline.toString());
    order.put("appointment", map("visitAt", visit.toString(), "confirmationDeadlineAt", deadline.toString()));
    // 预约不走线上支付，但保留 payment 子结构，让前端共用同一套订单渲染逻辑。
    order.put("payment", map("status", "offline_unpaid", "paidAmount", 0));
    store.save(order);
    store.audit(actor.id(), "appointment.create", text(order, "id"));
    // 事务提交后才发短信：提交前发，回滚了用户却收到"预约成功"。
    notifications.notifyAfterCommit(order, "submitted");
    return order;
  }

  /** 仅这两个状态算"进行中"：既占用库存，也阻止同一买家重复预约。 */
  private boolean active(Map<String, Object> order) {
    return List.of("pending_confirmation", "reservation_confirmed").contains(text(order, "status"));
  }

  /**
   * 取消预约：释放库存锁并记录原因。
   *
   * <p>先断言当前状态合法（只允许从两个进行中状态取消），再改状态、释放 {@code pet.active_order_id}。
   * 释放必须在 {@code store.save} 之前完成，保证同一事务内库存与订单状态一致。
   */
  public void cancel(Map<String, Object> order, String reason, String actor) {
    BusinessRepository.state(order, "pending_confirmation", "reservation_confirmed");
    order.put("status", "cancelled");
    order.put("cancelReason", reason);
    order.put("cancelledAt", Instant.now().toString());
    orders.release(order);
    store.save(order);
    store.audit(actor, "appointment.cancel", text(order, "id"));
  }

  /**
   * 预约的三种后续动作：{@code confirm} 确认、{@code cancel} 取消、{@code complete} 到店交付。
   *
   * <p>权限刻意不对称：接待员工可以完成交付（日常高频），但确认/取消需要店长决策（影响成交与库存）。
   *
   * <p>完成交付时会二次核对库存归属——{@code pet.active_order_id} 必须仍指向本订单且状态为
   * {@code reserved}。这是防止并发下两笔预约各自认为锁在自己手里。
   */
  public void process(Map<String, Object> order, String action, Map<String, Object> body, AuthService.Actor actor) {
    // 员工只能完成到店交付；确认或取消预约仍由店长决策。
    if ("complete".equals(action)) AuthService.staff(actor);
    else AuthService.role(actor, "admin");
    require(order.containsKey("appointment"), 409, "ORDER_STATE_CONFLICT", "该记录不是到店预约");
    Input input = new Input(body, "reason,paymentReceived,deliveryConfirmed,quarantineVerified");
    require(List.of("confirm", "cancel", "complete").contains(action), 404, "RESOURCE_NOT_FOUND", "预约操作不存在");
    if (action.equals("cancel")) {
      cancel(order, input.str("reason", 1, 200), actor.id());
      notifications.notifyAfterCommit(order, "cancelled");
      return;
    }
    // 确认与完成都要求预约未过期：过期单必须先由定时任务置为 expired，不能靠人工操作抢救。
    require(active(order) && Instant.parse(text(order, "expiresAt")).isAfter(Instant.now()),
        409, "APPOINTMENT_EXPIRED", "预约已过期或状态已变化，请刷新");
    Map<String, Object> appointment = object(order, "appointment");
    if (action.equals("confirm")) {
      BusinessRepository.state(order, "pending_confirmation");
      appointment.put("confirmedAt", Instant.now().toString());
      order.put("status", "reservation_confirmed");
      // 确认后截止时间改为到店时刻 + 2 小时，给迟到留缓冲，但不会无限期占住库存。
      order.put("expiresAt", Instant.parse(text(appointment, "visitAt")).plus(Duration.ofHours(2)).toString());
    } else {
      BusinessRepository.state(order, "reservation_confirmed");
      // 三个 must-be-true 字段是线下交付的留痕：收款、交付、检疫都需店员当场勾选确认。
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
      // release 带订单号做条件更新，只有锁确实属于本单时才释放。
      store.release(text(pet, "id"), text(order, "id"));
      store.save(pet);
    }
    store.save(order);
    store.audit(actor.id(), "appointment." + action, text(order, "id"));
    notifications.notifyAfterCommit(order, action.equals("confirm") ? "confirmed" : "completed");
  }

  /**
   * 批量过期清理：把超过 {@code expiresAt} 的进行中预约置为 {@code expired} 并释放库存。
   *
   * <p>触发点：一分钟一次的定时任务（{@code PaymentWorker.reconcile}，持全局写锁执行）。
 * 创建预约时不再顺手清理，因为预约下单不持有全局锁。
   * 只遍历订单全表而非增量查询，是因为单店数据量小，换取逻辑简单、无需额外索引。
   *
   * <p><b>注意</b>：这里不做权限校验，属于系统级维护操作，不能由 HTTP 入口直接触发。
   */
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
