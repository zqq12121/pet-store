package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 微信码响应使用替身，账号/会话为独立 H2，状态推进与权限由真实业务执行。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:mini_qr;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.sql.init.mode=always", "app.mock-providers=true", "app.redis-enabled=false",
    "app.admin-password=QrTestPassword2026!", "app.storage=./target/qr-test-files"})
@ActiveProfiles("test")
class WechatMiniQrTest {
  @Autowired WechatMiniQrService qr;
  @Autowired TemporaryStore temp;
  @Autowired AuthService auth;
  @Autowired BusinessRepository store;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean GraphCaptchaService captcha;
  MockedStatic<ProviderSupport> env;
  HttpClient client;
  AuthService.Actor actor;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    env = mockStatic(ProviderSupport.class, CALLS_REAL_METHODS);
    env.when(() -> ProviderSupport.env("PAW_WECHAT_MINI_APP_ID")).thenReturn("test-mini-qr");
    env.when(() -> ProviderSupport.env("PAW_WECHAT_MINI_APP_SECRET")).thenReturn("private-test-secret");
    env.when(() -> ProviderSupport.env("PAW_WECHAT_MINI_ENV_VERSION")).thenReturn("develop");
    client = mock(HttpClient.class);
    Object target = AopTestUtils.getTargetObject(qr);
    ReflectionTestUtils.setField(target, "client", client);
    temp.take("mini-api-token:test-mini-qr");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
      HttpRequest request = invocation.getArgument(0);
      HttpResponse<byte[]> response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(200);
      when(response.body()).thenReturn(request.uri().getPath().endsWith("stable_token")
          ? write(map("access_token", "private-provider-token", "expires_in", 7200)).getBytes(StandardCharsets.UTF_8)
          : new byte[] {(byte)137,80,78,71,13,10,26,10,0});
      return response;
    });
    actor = new TransactionTemplate(transactions).execute(status -> {
      var user = store.create("user", null, null, map("phone", null, "nickname", "扫码测试"));
      String id = text(user, "id");
      store.create("wechat", id, "test-mini-qr:" + id, map("appId", "test-mini-qr", "openid", UUID.randomUUID().toString()));
      return auth.identify("Bearer " + auth.login(user, "buyer").get("accessToken"));
    });
  }

  @AfterEach void cleanup() { env.close(); }
  Map<String, Object> create() { return qr.create(map("returnPath", "/profile"), UUID.randomUUID().toString()); }
  Map<String, Object> proof(Map<String, Object> created) { return map("browserToken", created.get("browserToken")); }

  @Test void requiresConsentCaptchaAndCorrectBrowserAndConsumesOnce() throws Exception {
    var created = create(); String id = text(created, "ticket");
    assertTrue(id.matches("[a-f0-9]{32}"));
    assertTrue(text(created, "image").startsWith("data:image/png;base64,"));
    verify(captcha).verify(map("returnPath", "/profile"));
    assertEquals("pending", qr.poll(id, proof(created), id).get("status"));
    assertFalse(qr.inspect(id, id).containsKey("browser"));
    assertEquals(403, assertThrows(ApiException.class, () -> qr.poll(id, map("browserToken", "x".repeat(43)), id)).status);
    assertEquals(401, assertThrows(ApiException.class, () -> qr.confirm(id, map("approved", true), null)).status);
    qr.confirm(id, map("approved", true), actor);
    assertEquals(409, assertThrows(ApiException.class, () -> qr.confirm(id, map("approved", true), actor)).status);
    var result = qr.poll(id, proof(created), id);
    assertEquals("authenticated", result.get("status"));
    assertEquals(actor.id(), auth.identify("Bearer " + object(result, "login").get("accessToken")).id());
    assertEquals("/profile", result.get("returnPath"));
    assertEquals("consumed", qr.poll(id, proof(created), id).get("status"));
    assertFalse(write(result).contains("private-provider-token"));
    assertFalse(write(result).contains("private-test-secret"));
    var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
    for (var request : requests.getAllValues()) assertEquals("api.weixin.qq.com", request.uri().getHost());
  }

  @Test void rejectCancelExpiryAndChangedAppNeverIssueSessions() {
    var rejected = create(); String id = text(rejected, "ticket");
    qr.confirm(id, map("approved", false), actor);
    assertEquals("rejected", qr.poll(id, proof(rejected), id).get("status"));
    var cancelled = create(); String cancelledId = text(cancelled, "ticket");
    qr.cancel(cancelledId, proof(cancelled));
    assertEquals(409, assertThrows(ApiException.class, () -> qr.confirm(cancelledId, map("approved", true), actor)).status);
    assertEquals("cancelled", qr.poll(cancelledId, proof(cancelled), cancelledId).get("status"));
    temp.take("mini-qr:" + cancelledId);
    assertEquals(410, assertThrows(ApiException.class, () -> qr.poll(cancelledId, proof(cancelled), cancelledId)).status);
    env.when(() -> ProviderSupport.env("PAW_WECHAT_MINI_APP_ID")).thenReturn("another-mini");
    assertEquals(410, assertThrows(ApiException.class, () -> qr.inspect(id, id)).status);
  }

  @Test void foreignPathsUnknownFieldsAndProviderErrorsNeverCreateUsableQr() throws Exception {
    for (String path : List.of("//foreign.test", "https://foreign.test", "/%2fattack"))
      assertEquals(400, assertThrows(ApiException.class, () -> qr.create(map("returnPath", path), "path-test")).status);
    assertEquals(400, assertThrows(ApiException.class, () -> qr.create(map("returnPath", "/", "ownerId", "other"), "field-test")).status);
    doThrow(new ApiException(400, "CAPTCHA_INVALID", "验证失败")).when(captcha).verify(anyMap());
    assertThrows(ApiException.class, this::create);
    verifyNoInteractions(client);
    reset(captcha);
    HttpResponse<byte[]> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"errcode\":40164}".getBytes(StandardCharsets.UTF_8));
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    assertEquals(503, assertThrows(ApiException.class, this::create).status);
  }

  @Test void tokenExpiryIsRetriedOnceAndInvalidImageIsNeverReturned() throws Exception {
    temp.put("mini-api-token:test-mini-qr", "stale-token", 100);
    HttpResponse<byte[]> invalid = mock(HttpResponse.class);
    when(invalid.statusCode()).thenReturn(200);
    when(invalid.body()).thenReturn("{\"errcode\":40014}".getBytes(StandardCharsets.UTF_8));
    HttpResponse<byte[]> fresh = mock(HttpResponse.class);
    when(fresh.statusCode()).thenReturn(200);
    when(fresh.body()).thenReturn(write(map("access_token", "new-token", "expires_in", 7200)).getBytes(StandardCharsets.UTF_8));
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(invalid, fresh, invalid);
    assertEquals(502, assertThrows(ApiException.class, this::create).status);
    verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test void unboundAndRevokedAccountsCannotApproveAndTimestampExpiryIsEnforced() {
    var created = create(); String id = text(created, "ticket");
    var unbound = new TransactionTemplate(transactions).execute(status -> {
      var user = store.create("user", null, null, map("phone", null, "nickname", "未绑定测试"));
      return auth.identify("Bearer " + auth.login(user, "buyer").get("accessToken"));
    });
    assertEquals(403, assertThrows(ApiException.class, () -> qr.confirm(id, map("approved", true), unbound)).status);
    auth.logout(actor);
    assertEquals(401, assertThrows(ApiException.class, () -> qr.confirm(id, map("approved", true), actor)).status);
    var state = read(temp.get("mini-qr:" + id));
    state.put("expiresAt", java.time.Instant.now().minusSeconds(1).toString());
    temp.put("mini-qr:" + id, write(state), 60);
    assertEquals(410, assertThrows(ApiException.class, () -> qr.poll(id, proof(created), id)).status);
  }

  @Test void atomicStateChangeAllowsOnlyOneConcurrentWinnerAndDoesNotResurrectExpiredQr() throws Exception {
    String key = "qr-race:" + UUID.randomUUID();
    temp.put(key, "confirmed", 60);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a = executor.submit(() -> temp.replace(key, "confirmed", "consumed"));
      var b = executor.submit(() -> temp.replace(key, "confirmed", "consumed"));
      assertNotEquals(a.get(), b.get());
    }
    temp.take(key);
    assertFalse(temp.replace(key, "confirmed", "consumed"));
  }
}
