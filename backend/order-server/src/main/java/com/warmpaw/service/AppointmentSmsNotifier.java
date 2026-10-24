package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 预约短信的登记入口：在业务事务内写 outbox，提交后再尝试立即发送。
 *
 * <p>这个类只做三件事：判断该不该发、把内容固化成快照、注册"提交后"回调。
 * 真正的发送逻辑在 {@link AppointmentSmsWorker}。
 *
 * <p><b>为什么强制要求事务</b>：如果调用方不在事务里，outbox 记录会独立提交，
 * 一旦订单后续校验失败，用户就会收到一条"预约成功"但实际不存在的短信。
 * 所以这里显式检查事务同步状态，缺失直接抛 {@link IllegalStateException}——
 * 这是编程错误，不该被业务代码捕获。
 *
 * <p><b>为什么提交后才发</b>：{@code afterCommit} 里的失败只记日志、不向上抛。
 * 此时订单已经提交、outbox 也已落库，定时扫描会在 30 秒内补发；
 * 若把异常抛给用户，会让人误以为下单失败而重复提交。
 */
@Service
public class AppointmentSmsNotifier {
  private static final Logger log = LoggerFactory.getLogger(AppointmentSmsNotifier.class);
  private final AppointmentSmsOutbox outbox;
  private final AppointmentSmsWorker worker;

  private final BusinessRepository store;

  public AppointmentSmsNotifier(AppointmentSmsOutbox outbox, AppointmentSmsWorker worker, BusinessRepository store) {
    this.store = store;
    this.outbox = outbox;
    this.worker = worker;
  }

  /**
   * 登记一条预约事件通知。
   *
   * <p><b>不发给"手填号码"</b>：微信静默登录的账号可能没有手机号，预约时手填的联系号码
   * 从未经过短信验证，向它发通知等于给任意号码发短信（可被用作短信轰炸）。
   * 只有 {@code contactPhone} 与账号绑定手机号一致时才发送。
   *
   * <p>时间按 {@code Asia/Shanghai} 格式化为 {@code yyyy-MM-dd HH:mm} 后再进模板，
   * 避免把 ISO-8601 的 UTC 字符串直接发给用户造成时区误读。
   *
   * <p>取消事件额外带上原因，模板可直接展示。
   *
   * @param event 事件名：submitted / confirmed / cancelled / completed / expired
   */
  public void notifyAfterCommit(Map<String, Object> order, String event) {
    // 手填联系号码没有通过短信核验，不自动向该号码发送通知。
    Map<String, Object> user = store.get("user", text(order, "ownerId"));
    if (!text(order, "contactPhone").equals(text(user, "phone"))) return;
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
