package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在预约事务内登记通知；事务回滚时 outbox 记录也一起回滚。 */
@Service
public class AppointmentSmsNotifier {
  private static final Logger log = LoggerFactory.getLogger(AppointmentSmsNotifier.class);
  private final AppointmentSmsOutbox outbox;
  private final AppointmentSmsWorker worker;

  public AppointmentSmsNotifier(AppointmentSmsOutbox outbox, AppointmentSmsWorker worker) {
    this.outbox = outbox;
    this.worker = worker;
  }

  public void notifyAfterCommit(Map<String, Object> order, String event) {
    // 提前保存本次状态的快照，避免同一事务内后续修改影响短信内容。
    String id = text(order, "id"), phone = text(order, "contactPhone");
    String visitAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Shanghai"))
        .format(Instant.parse(text(object(order, "appointment"), "visitAt")));
    Map<String, Object> parameters = map("orderId", id, "visitAt", visitAt);
    if (event.equals("cancelled")) parameters.put("reason", text(order, "cancelReason"));
    // 必须绑定业务事务；订单状态和通知任务只能同时提交或同时回滚。
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("预约短信必须在业务事务内登记");
    }
    Instant now = Instant.now();
    if (!outbox.enqueue(id, event, phone, parameters, now)) return;
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        try {
          worker.dispatch(id + "-" + event);
        } catch (RuntimeException e) {
          // 订单已经提交，队列也已持久化；交给定时扫描恢复，不向买家谎报下单失败。
          log.error("预约短信即时调度失败，等待扫描恢复：orderId={}, event={}", id, event);
        }
      }
    });
  }
}
