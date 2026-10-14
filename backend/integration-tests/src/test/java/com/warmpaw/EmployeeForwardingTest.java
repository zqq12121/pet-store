package com.warmpaw;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.remote.AdminTransactionController;
import com.warmpaw.remote.OrderAdminClient;
import com.warmpaw.service.AuthService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 管理服务转发边界：员工凭证和幂等键原样传递，店长操作在转发前拒绝。 */
class EmployeeForwardingTest {
  private feign.Response reply() {
    return feign.Response.builder().status(200).reason("OK").headers(Map.of())
        .request(feign.Request.create(feign.Request.HttpMethod.GET,"http://test/orders",
            Map.of(),null,StandardCharsets.UTF_8,new feign.RequestTemplate()))
        .body("{\"code\":\"OK\"}",StandardCharsets.UTF_8).build();
  }

  @Test void receptionForwardsIdentityAndIdempotencyButCannotConfirmOrCancel() throws Exception {
    var client=mock(OrderAdminClient.class);
    var auth=mock(AuthService.class);
    when(auth.identify("Bearer test-staff")).thenReturn(new AuthService.Actor("admin_staff","staff",null,"test"));
    var controller=new AdminTransactionController(client,auth);
    var request=new MockHttpServletRequest();
    request.addHeader("Authorization","Bearer test-staff");
    request.addHeader("Idempotency-Key","00000000-0000-0000-0000-000000000001");
    var headers=Map.of("Authorization","Bearer test-staff","Idempotency-Key","00000000-0000-0000-0000-000000000001");
    when(client.orders(anyMap(),anyMap())).thenAnswer(call->reply());
    when(client.appointment(anyString(),anyString(),anyMap(),anyMap())).thenAnswer(call->reply());
    assertEquals(200,controller.orders(Map.of(),request).getStatusCode().value());
    verify(client).orders(Map.of(),headers);
    assertEquals(200,controller.appointment("order_test","complete",Map.of(),request).getStatusCode().value());
    verify(client).appointment("order_test","complete",Map.of(),headers);
    for(String action:java.util.List.of("confirm","cancel"))
      assertEquals(403,assertThrows(ApiException.class,()->controller.appointment("order_test",action,Map.of(),request)).status);
    verifyNoMoreInteractions(client);
  }

  @Test void managerOnlyOperationsNeverReachOrderServiceWithEmployeeToken() {
    var client=mock(OrderAdminClient.class);
    var auth=mock(AuthService.class);
    when(auth.identify("Bearer test-staff")).thenReturn(new AuthService.Actor("admin_staff","staff",null,"test"));
    var controller=new AdminTransactionController(client,auth);
    var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer test-staff");
    assertEquals(403,assertThrows(ApiException.class,()->controller.dashboard(Map.of(),request)).status);
    assertEquals(403,assertThrows(ApiException.class,()->controller.afterSales(Map.of(),request)).status);
    assertEquals(403,assertThrows(ApiException.class,()->controller.review("sale_test",Map.of(),request)).status);
    assertEquals(403,assertThrows(ApiException.class,()->controller.retryRefund("refund_test",Map.of(),request)).status);
    verifyNoInteractions(client);
  }
}
