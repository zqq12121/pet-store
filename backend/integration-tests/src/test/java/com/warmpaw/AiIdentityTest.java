package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.warmpaw.common.ApiException;
import com.warmpaw.controller.AuthController;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** AI 身份桥接使用真实不透明凭证校验，不能接受伪造游客或管理员身份。 */
class AiIdentityTest {
  @Test
  void validatesGuestAndBuyerWithoutExposingCredentials() {
    BusinessRepository store = mock(BusinessRepository.class);
    TemporaryStore temporary = new TemporaryStore(null, false);
    AuthService auth = new AuthService(store, temporary, mock(SmsGateway.class), true);
    GuestService guests = new GuestService(temporary, auth);
    AuthController controller = new AuthController(guests, auth, mock(WechatLoginService.class));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Guest-Token", "forged");
    assertEquals(401, assertThrows(ApiException.class, () -> controller.aiIdentity(request)).status);
    request.removeHeader("X-Guest-Token");
    String guestToken = text(guests.create("127.0.0.1"), "guestToken");
    request.addHeader("X-Guest-Token", guestToken);
    String response = write(controller.aiIdentity(request).getBody());
    assertTrue(response.contains("guest"));
    assertFalse(response.contains(guestToken));

    request.addHeader("Authorization", "Bearer buyer-token");
    assertEquals(400, assertThrows(ApiException.class, () -> controller.aiIdentity(request)).status);
    request.removeHeader("X-Guest-Token");
    when(store.byKey("session", hash("buyer-token"))).thenReturn(map(
        "status", "active", "expiresAt", Instant.now().plusSeconds(60).toString(), "ownerId", "user_1", "role", "buyer"));
    when(store.get("user", "user_1")).thenReturn(map("phone", "13800000000"));
    response = write(controller.aiIdentity(request).getBody());
    assertTrue(response.contains("user_1"));
    assertFalse(response.contains("13800000000"));
    assertFalse(response.contains("buyer-token"));
    assertEquals(403, assertThrows(ApiException.class, () -> controller.aiAdminIdentity(request)).status);

    when(store.byKey("session", hash("buyer-token"))).thenReturn(map(
        "status", "active", "expiresAt", Instant.now().plusSeconds(60).toString(), "ownerId", "admin_1", "role", "admin"));
    when(store.get("admin", "admin_1")).thenReturn(map("phone", "13800000000"));
    assertEquals(403, assertThrows(ApiException.class, () -> controller.aiIdentity(request)).status);
    response = write(controller.aiAdminIdentity(request).getBody());
    assertTrue(response.contains("admin_1"));
    assertFalse(response.contains("buyer-token"));
    assertFalse(response.contains("13800000000"));
    request.addHeader("X-Guest-Token", guestToken);
    assertEquals(403, assertThrows(ApiException.class, () -> controller.aiAdminIdentity(request)).status);
    request.removeHeader("X-Guest-Token");
    request.removeHeader("Authorization");
    assertEquals(401, assertThrows(ApiException.class, () -> controller.aiAdminIdentity(request)).status);
  }
}
