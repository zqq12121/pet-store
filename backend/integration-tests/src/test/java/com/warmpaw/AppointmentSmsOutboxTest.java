package com.warmpaw;

import static org.junit.jupiter.api.Assertions.*;

import com.warmpaw.service.AppointmentSmsOutbox;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 用真实 SQL 验证租约恢复、失败上限和事务回滚，不访问短信平台。 */
class AppointmentSmsOutboxTest {
  JdbcTemplate jdbc;
  AppointmentSmsOutbox outbox;
  TransactionTemplate tx;
  final Instant start = Instant.parse("2026-09-21T00:00:00Z");
  final String key = "order_test-submitted";

  @BeforeEach
  void setup() {
    var ds = new DriverManagerDataSource(
        "jdbc:h2:mem:sms_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    outbox = new AppointmentSmsOutbox(jdbc);
    jdbc.update("INSERT INTO orders(id,status,version,created_at,updated_at) VALUES(?,'pending_confirmation',1,?,?)",
        "order_test", start.toString(), start.toString());
  }

  void enqueue() {
    tx.executeWithoutResult(s -> outbox.enqueue("order_test", "submitted", "00000000000", Map.of(), start));
  }

  String status() {
    return jdbc.queryForObject("SELECT status FROM appointment_sms_outbox WHERE event_key=?", String.class, key);
  }

  @Test
  void rollbackRemovesNoticeAndDuplicateEventIsIgnored() {
    tx.executeWithoutResult(s -> {
      outbox.enqueue("order_test", "submitted", "00000000000", Map.of(), start);
      s.setRollbackOnly();
    });
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM appointment_sms_outbox", Integer.class));
    enqueue();
    assertEquals(Boolean.FALSE, tx.execute(s -> outbox.enqueue("order_test", "submitted", "00000000000", Map.of(), start)));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM appointment_sms_outbox", Integer.class));
  }

  @Test
  void activeLeaseCannotBeClaimedAgain() {
    enqueue();
    assertNotNull(outbox.claim(key, start));
    assertNull(outbox.claim(key, start.plusSeconds(119)));
    assertEquals(2, outbox.claim(key, start.plusSeconds(121)).attempts());
  }

  @Test
  void staleFailureCannotOverwriteReclaimedLease() {
    enqueue();
    var first = outbox.claim(key, start);
    outbox.claim(key, start.plusSeconds(121));
    outbox.retry(key, first.attempts(), start.plusSeconds(122), "UPSTREAM_ERROR");
    assertEquals("sending", status());
    outbox.sent(key, first.attempts(), start.plusSeconds(123));
    assertEquals("sending", status());
    outbox.sent(key, 2, start.plusSeconds(124));
    assertEquals("sent", status());
  }

  @Test
  void repeatedProcessCrashesStopAtEightClaims() {
    enqueue();
    for (int i = 0; i < 8; i++) {
      assertEquals(i + 1, outbox.claim(key, start.plusSeconds(i * 121L)).attempts());
    }
    assertNull(outbox.claim(key, start.plusSeconds(8 * 121L)));
    assertEquals("failed", status());
  }

  @Test
  void retryWaitsForBackoffAndEndsAtLimit() {
    enqueue();
    var first = outbox.claim(key, start);
    outbox.retry(key, first.attempts(), start, "UPSTREAM_ERROR");
    assertNull(outbox.claim(key, start.plusSeconds(59)));
    assertEquals(2, outbox.claim(key, start.plusSeconds(60)).attempts());
    jdbc.update("UPDATE appointment_sms_outbox SET attempts=8 WHERE event_key=?", key);
    outbox.retry(key, 8, start.plusSeconds(60), "UPSTREAM_ERROR");
    assertEquals("failed", status());
  }
}
