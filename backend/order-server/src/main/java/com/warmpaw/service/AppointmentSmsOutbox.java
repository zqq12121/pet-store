package com.warmpaw.service;

import static com.warmpaw.common.Json.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 预约短信 outbox；业务状态与通知任务在同一数据库事务内提交。 */
@Repository
public class AppointmentSmsOutbox {
  private static final int MAX_ATTEMPTS = 8;
  private final JdbcTemplate jdbc;

  public AppointmentSmsOutbox(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Worker 使用的不可变通知快照，不再回查可能已变化的订单联系人。 */
  public record Notice(
      String key,
      String orderId,
      String event,
      String phone,
      Map<String, Object> parameters,
      int attempts) {}

  /** 唯一键为订单加事件；重复的 HTTP 幂等回放不会生成第二条短信。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean enqueue(
      String orderId,
      String event,
      String phone,
      Map<String, Object> parameters,
      Instant now) {
    String key = orderId + "-" + event;
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM appointment_sms_outbox WHERE event_key=?", Integer.class, key);
    if (count != null && count > 0) return false;
    try {
      jdbc.update(
          "INSERT INTO appointment_sms_outbox"
              + "(event_key,order_id,event,phone,parameters,status,attempts,next_attempt_at,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,'pending',0,?,?,?)",
          key,
          orderId,
          event,
          phone,
          write(parameters),
          now.toString(),
          now.toString(),
          now.toString());
      return true;
    } catch (DuplicateKeyException ignored) {
      // 多实例极短竞态由数据库唯一键收口，调用方无需再次登记提交回调。
      return false;
    }
  }

  /** 用租约原子认领一条任务，避免多个 order-server 同时发送同一事件。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Notice claim(String key, Instant now) {
    Instant lease = now.plus(Duration.ofMinutes(2));
    int changed =
        jdbc.update(
            "UPDATE appointment_sms_outbox SET status='sending',attempts=attempts+1,"
                + "locked_until=?,updated_at=? WHERE event_key=? "
                + "AND status IN ('pending','retry','sending') AND next_attempt_at<=? "
                + "AND (locked_until IS NULL OR locked_until<=?)",
            lease.toString(),
            now.toString(),
            key,
            now.toString(),
            now.toString());
    if (changed != 1) return null;
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT event_key,order_id,event,phone,parameters,attempts "
                + "FROM appointment_sms_outbox WHERE event_key=?",
            key);
    return new Notice(
        text(row, "event_key"),
        text(row, "order_id"),
        text(row, "event"),
        text(row, "phone"),
        read(text(row, "parameters")),
        ((Number) row.get("attempts")).intValue());
  }

  public List<String> due(Instant now) {
    return jdbc.queryForList(
        "SELECT event_key FROM appointment_sms_outbox "
            + "WHERE status IN ('pending','retry','sending') AND next_attempt_at<=? "
            + "AND (locked_until IS NULL OR locked_until<=?) ORDER BY created_at LIMIT 20",
        String.class,
        now.toString(),
        now.toString());
  }

  /** 后台仅展示汇总数量，不返回手机号或模板参数。 */
  public long pendingCount() {
    Long value =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM appointment_sms_outbox WHERE status IN ('pending','retry','sending')",
            Long.class);
    return value == null ? 0 : value;
  }

  public long failedCount() {
    Long value =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM appointment_sms_outbox WHERE status='failed'", Long.class);
    return value == null ? 0 : value;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void sent(String key, Instant now) {
    jdbc.update(
        "UPDATE appointment_sms_outbox SET status='sent',sent_at=?,locked_until=NULL,"
            + "last_error=NULL,updated_at=? WHERE event_key=? AND status='sending'",
        now.toString(),
        now.toString(),
        key);
  }

  /** 指数退避最多八次；最终失败保留在表内，供后台排查而不是静默丢弃。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retry(String key, int attempts, Instant now, String errorCode) {
    boolean exhausted = attempts >= MAX_ATTEMPTS;
    long delayMinutes = Math.min(120, 1L << Math.min(7, Math.max(0, attempts - 1)));
    jdbc.update(
        "UPDATE appointment_sms_outbox SET status=?,next_attempt_at=?,locked_until=NULL,"
            + "last_error=?,updated_at=? WHERE event_key=? AND status='sending'",
        exhausted ? "failed" : "retry",
        now.plus(Duration.ofMinutes(delayMinutes)).toString(),
        errorCode,
        now.toString(),
        key);
  }
}
