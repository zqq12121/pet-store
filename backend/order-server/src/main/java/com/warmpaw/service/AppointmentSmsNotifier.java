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

/** 预约提交后通知买家；短信失败不改变已提交的预约和库存状态。 */
@Service
public class AppointmentSmsNotifier {
  private static final Logger log = LoggerFactory.getLogger(AppointmentSmsNotifier.class);
  private final AuthService auth;
  private final SmsGateway gateway;

  public AppointmentSmsNotifier(AuthService auth, SmsGateway gateway) {
    this.auth = auth;
    this.gateway = gateway;
  }

  public void notifyAfterCommit(Map<String, Object> order, String event) {
    // 提前保存本次状态的快照，避免同一事务内后续修改影响短信内容。
    String id = text(order, "id"), phone = text(order, "contactPhone");
    String visitAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Shanghai"))
        .format(Instant.parse(text(object(order, "appointment"), "visitAt")));
    Map<String, Object> parameters = map("orderId", id, "visitAt", visitAt);
    if (event.equals("cancelled")) parameters.put("reason", text(order, "cancelReason"));
    // 必须绑定业务事务；回滚时不会生成收件箱文件，也不会调用真实短信平台。
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("预约短信必须在业务事务内登记");
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        try {
          if (auth.local()) {
            auth.localAppointmentNotice(id + "-" + event,
                map("status", "simulated", "phone", phone, "event", event, "parameters", parameters));
          } else {
            gateway.sendAppointment(phone, event, parameters);
          }
        } catch (RuntimeException e) {
          // 不输出手机号、短信内容或密钥；未配置与平台拒绝均明确记录为失败。
          log.error("预约短信通知失败：orderId={}, event={}, error={}", id, event, e.getMessage());
        }
      }
    });
  }
}
