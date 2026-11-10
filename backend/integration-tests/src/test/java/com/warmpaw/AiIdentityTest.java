package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.controller.AuthController;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** AI 身份桥接使用真实 JWT 校验，不能接受伪造游客或管理员身份。 */
class AiIdentityTest {
  @Test
  void validatesGuestAndBuyerWithoutExposingCredentials() {
    BusinessRepository store = mock(BusinessRepository.class);
    TemporaryStore temporary = new TemporaryStore(null, false);
    AuthService auth = new AuthService(store, temporary, mock(SmsGateway.class), mock(GraphCaptchaService.class), new PasswordService(), new com.warmpaw.service.JwtService("integration-test-only-jwt-secret-0123456789", true), true);
    GuestService guests = new GuestService(temporary, auth);
    AuthController controller = new AuthController(guests, auth, mock(GraphCaptchaService.class), new com.warmpaw.web.BuyerSessionCookie(false));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Guest-Token", "forged");
    assertEquals(401, assertThrows(ApiException.class, () -> controller.aiIdentity(request)).status);
    request.removeHeader("X-Guest-Token");
    String guestToken = text(guests.create("127.0.0.1"), "guestToken");
    request.addHeader("X-Guest-Token", guestToken);
    String response = write(controller.aiIdentity(request).getBody());
    assertTrue(response.contains("guest"));
    assertFalse(response.contains(guestToken));

    // 签发真实 JWT：买家 user_1
    String buyerToken = text(auth.login(map("id", "user_1"), "buyer"), "accessToken");
    request.addHeader("Authorization", "Bearer " + buyerToken);
    assertEquals(400, assertThrows(ApiException.class, () -> controller.aiIdentity(request)).status);
    request.removeHeader("X-Guest-Token");
    when(store.find("user", "user_1")).thenReturn(map("phone", "13800000000"));
    response = write(controller.aiIdentity(request).getBody());
    assertTrue(response.contains("user_1"));
    assertFalse(response.contains("13800000000"));
    assertFalse(response.contains(buyerToken));
    assertEquals(403, assertThrows(ApiException.class, () -> controller.aiAdminIdentity(request)).status);

    // 换成店长 admin_1 的 JWT
    String adminToken = text(auth.login(map("id", "admin_1"), "admin"), "accessToken");
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + adminToken);
    when(store.find("admin", "admin_1")).thenReturn(map("phone", "13800000000"));
    assertEquals(403, assertThrows(ApiException.class, () -> controller.aiIdentity(request)).status);
    response = write(controller.aiAdminIdentity(request).getBody());
    assertTrue(response.contains("admin_1"));
    assertFalse(response.contains(adminToken));
    assertFalse(response.contains("13800000000"));
    request.addHeader("X-Guest-Token", guestToken);
    assertEquals(403, assertThrows(ApiException.class, () -> controller.aiAdminIdentity(request)).status);
    request.removeHeader("X-Guest-Token");
    request.removeHeader("Authorization");
    assertEquals(401, assertThrows(ApiException.class, () -> controller.aiAdminIdentity(request)).status);
  }
}
