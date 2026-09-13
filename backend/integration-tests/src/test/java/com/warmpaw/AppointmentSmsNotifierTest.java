package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.common.ProviderSupport;
import com.warmpaw.service.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 隔离真实网络，验证提交边界及未配置时绝不伪装发送成功。 */
class AppointmentSmsNotifierTest {
  Map<String, Object> order() {
    return map("id", "order_sms_test", "contactPhone", "13900000000",
        "appointment", map("visitAt", "2026-09-14T02:00:00Z"));
  }

  @Test
  void onlyCommittedTransactionsSendAndLocalModeNeverCallsGateway() {
    AuthService auth = mock(AuthService.class);
    SmsGateway gateway = mock(SmsGateway.class);
    var notifier = new AppointmentSmsNotifier(auth, gateway);
    when(auth.local()).thenReturn(true);
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      notifier.notifyAfterCommit(order(), "submitted");
      verifyNoInteractions(gateway);
      verify(auth, never()).localAppointmentNotice(anyString(), anyMap());
      var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
      callback.afterCommit();
      verify(auth).localAppointmentNotice(eq("order_sms_test-submitted"), argThat(notice ->
          "simulated".equals(notice.get("status"))
              && "13900000000".equals(notice.get("phone"))
              && "2026-09-14 10:00".equals(object(notice, "parameters").get("visitAt"))));
      verifyNoInteractions(gateway);
    } finally {
      TransactionSynchronizationManager.clear();
    }
    reset(auth, gateway);
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      notifier.notifyAfterCommit(order(), "submitted");
      // 回滚只调用完成回调，不执行提交后通知。
      TransactionSynchronizationManager.getSynchronizations().getFirst().afterCompletion(1);
      verifyNoInteractions(auth, gateway);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void realModeUsesGatewayAndProviderFailureDoesNotUndoCommittedOperation() {
    AuthService auth = mock(AuthService.class);
    SmsGateway gateway = mock(SmsGateway.class);
    var notifier = new AppointmentSmsNotifier(auth, gateway);
    // 平台拒绝属于通知失败，不能让已经提交的预约接口再返回失败。
    doThrow(new ApiException(502, "UPSTREAM_ERROR", "短信平台暂未受理"))
        .when(gateway).sendAppointment(anyString(), eq("cancelled"), anyMap());
    for (String event : java.util.List.of("submitted", "confirmed", "cancelled", "expired")) {
      TransactionSynchronizationManager.initSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        var order = order();
        order.put("cancelReason", "门店临时休息");
        notifier.notifyAfterCommit(order, event);
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit());
        verify(gateway).sendAppointment(eq("13900000000"), eq(event), argThat(parameters ->
            "order_sms_test".equals(parameters.get("orderId"))
                && (!event.equals("cancelled") || "门店临时休息".equals(parameters.get("reason")))));
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
    verify(auth, never()).localAppointmentNotice(anyString(), anyMap());
  }

  @Test
  void missingAppointmentTemplateCannotFallBackToLoginOrWriteInbox() {
    try (var env = mockStatic(ProviderSupport.class)) {
      // 即使验证码配置齐全，缺少预约模板也必须在发起网络请求前失败。
      env.when(() -> ProviderSupport.env(anyString())).thenReturn("configured");
      for (String event : java.util.List.of("submitted", "confirmed", "cancelled", "expired")) {
        env.when(() -> ProviderSupport.env("PAW_SMS_APPOINTMENT_" + event.toUpperCase(java.util.Locale.ROOT) + "_TEMPLATE"))
            .thenReturn("");
        assertThrows(ApiException.class, () -> new SmsGateway().sendAppointment("13900000000", event, map()));
      }
      AuthService auth = mock(AuthService.class);
      var notifier = new AppointmentSmsNotifier(auth, new SmsGateway());
      TransactionSynchronizationManager.initSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        notifier.notifyAfterCommit(order(), "confirmed");
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit());
        verify(auth, never()).localAppointmentNotice(anyString(), anyMap());
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
  }
}
