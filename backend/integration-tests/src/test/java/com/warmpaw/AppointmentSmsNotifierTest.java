package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.common.ProviderSupport;
import com.warmpaw.service.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 隔离真实网络，验证通知先持久化、提交后发送、失败重试和事件幂等。 */
class AppointmentSmsNotifierTest {
  Map<String, Object> order() {
    return map(
        "id",
        "order_sms_test",
        "contactPhone",
        "13900000000",
        "appointment",
        map("visitAt", "2026-09-14T02:00:00Z"));
  }

  @Test
  void transactionPersistsBeforeCommitAndRollbackNeverDispatches() {
    AppointmentSmsOutbox outbox = mock(AppointmentSmsOutbox.class);
    AppointmentSmsWorker worker = mock(AppointmentSmsWorker.class);
    var notifier = new AppointmentSmsNotifier(outbox, worker);
    when(outbox.enqueue(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn(true);

    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      notifier.notifyAfterCommit(order(), "submitted");
      verify(outbox)
          .enqueue(
              eq("order_sms_test"),
              eq("submitted"),
              eq("13900000000"),
              argThat(p -> "2026-09-14 10:00".equals(p.get("visitAt"))),
              any());
      verifyNoInteractions(worker);
      TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
      verify(worker).dispatch("order_sms_test-submitted");
    } finally {
      TransactionSynchronizationManager.clear();
    }

    reset(outbox, worker);
    when(outbox.enqueue(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn(true);
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      notifier.notifyAfterCommit(order(), "submitted");
      // 回滚不会触发提交回调；outbox 行也会随外层业务事务一起回滚。
      TransactionSynchronizationManager.getSynchronizations().getFirst().afterCompletion(1);
      verifyNoInteractions(worker);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void duplicateBusinessEventDoesNotRegisterAnotherDispatch() {
    AppointmentSmsOutbox outbox = mock(AppointmentSmsOutbox.class);
    AppointmentSmsWorker worker = mock(AppointmentSmsWorker.class);
    var notifier = new AppointmentSmsNotifier(outbox, worker);
    when(outbox.enqueue(anyString(), anyString(), anyString(), anyMap(), any())).thenReturn(false);
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      notifier.notifyAfterCommit(order(), "confirmed");
      assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
      verifyNoInteractions(worker);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void workerMarksSuccessAndSchedulesProviderFailures() {
    AuthService auth = mock(AuthService.class);
    SmsGateway gateway = mock(SmsGateway.class);
    AppointmentSmsOutbox outbox = mock(AppointmentSmsOutbox.class);
    var worker = new AppointmentSmsWorker(auth, gateway, outbox);
    var notice =
        new AppointmentSmsOutbox.Notice(
            "order_sms_test-cancelled",
            "order_sms_test",
            "cancelled",
            "13900000000",
            map("orderId", "order_sms_test", "reason", "门店临时休息"),
            1);
    when(outbox.claim(eq(notice.key()), any())).thenReturn(notice);
    doThrow(new ApiException(502, "UPSTREAM_ERROR", "短信平台暂未受理"))
        .when(gateway)
        .sendAppointment(anyString(), eq("cancelled"), anyMap());

    assertDoesNotThrow(() -> worker.dispatch(notice.key()));
    verify(outbox).retry(eq(notice.key()), eq(1), any(Instant.class), eq("UPSTREAM_ERROR"));
    verify(outbox, never()).sent(anyString(), any());

    reset(gateway, outbox);
    when(outbox.claim(eq(notice.key()), any())).thenReturn(notice);
    worker.dispatch(notice.key());
    verify(gateway)
        .sendAppointment(
            eq("13900000000"),
            eq("cancelled"),
            argThat(p -> "门店临时休息".equals(p.get("reason"))));
    verify(outbox).sent(eq(notice.key()), any());
  }

  @Test
  void localModeUsesInboxAndCompletedEventHasDedicatedTemplate() {
    AuthService auth = mock(AuthService.class);
    SmsGateway gateway = mock(SmsGateway.class);
    AppointmentSmsOutbox outbox = mock(AppointmentSmsOutbox.class);
    var worker = new AppointmentSmsWorker(auth, gateway, outbox);
    var notice =
        new AppointmentSmsOutbox.Notice(
            "order_sms_test-completed",
            "order_sms_test",
            "completed",
            "13900000000",
            map("orderId", "order_sms_test", "visitAt", "2026-09-14 10:00"),
            1);
    when(auth.local()).thenReturn(true);
    when(outbox.claim(eq(notice.key()), any())).thenReturn(notice);
    worker.dispatch(notice.key());
    verify(auth)
        .localAppointmentNotice(
            eq(notice.key()), argThat(n -> "simulated".equals(n.get("status"))));
    verifyNoInteractions(gateway);
    verify(outbox).sent(eq(notice.key()), any());

    try (var env = mockStatic(ProviderSupport.class)) {
      env.when(() -> ProviderSupport.env(anyString())).thenReturn("configured");
      env.when(() -> ProviderSupport.env("PAW_SMS_APPOINTMENT_COMPLETED_TEMPLATE")).thenReturn("");
      assertThrows(
          ApiException.class,
          () -> new SmsGateway().sendAppointment("13900000000", "completed", map()));
    }
  }
}
