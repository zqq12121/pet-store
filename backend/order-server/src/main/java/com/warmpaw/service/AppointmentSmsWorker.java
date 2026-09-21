package com.warmpaw.service;

import static com.warmpaw.common.Json.map;

import com.warmpaw.common.ApiException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 提交后发送预约短信；失败写回重试时间，定时任务负责进程恢复后的补发。 */
@Component
public class AppointmentSmsWorker {
  private static final Logger log = LoggerFactory.getLogger(AppointmentSmsWorker.class);
  private final AuthService auth;
  private final SmsGateway gateway;
  private final AppointmentSmsOutbox outbox;

  public AppointmentSmsWorker(
      AuthService auth, SmsGateway gateway, AppointmentSmsOutbox outbox) {
    this.auth = auth;
    this.gateway = gateway;
    this.outbox = outbox;
  }

  public void dispatch(String key) {
    Instant now = Instant.now();
    AppointmentSmsOutbox.Notice notice = outbox.claim(key, now);
    if (notice == null) return;
    try {
      if (auth.local()) {
        auth.localAppointmentNotice(
            notice.key(),
            map(
                "status",
                "simulated",
                "phone",
                notice.phone(),
                "event",
                notice.event(),
                "parameters",
                notice.parameters()));
      } else {
        gateway.sendAppointment(notice.phone(), notice.event(), notice.parameters());
      }
      // attempts 随每次认领递增，旧 Worker 不能覆盖新租约的结果。
      outbox.sent(notice.key(), notice.attempts(), Instant.now());
    } catch (RuntimeException e) {
      String code = e instanceof ApiException api ? api.code : "INTERNAL_ERROR";
      outbox.retry(notice.key(), notice.attempts(), Instant.now(), code);
      // 不记录手机号、模板参数和平台密钥，只保留订单事件及稳定错误码。
      log.error(
          "预约短信发送失败：orderId={}, event={}, attempt={}, code={}",
          notice.orderId(),
          notice.event(),
          notice.attempts(),
          code);
    }
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 30000)
  public void retryDue() {
    for (String key : outbox.due(Instant.now())) dispatch(key);
  }
}
