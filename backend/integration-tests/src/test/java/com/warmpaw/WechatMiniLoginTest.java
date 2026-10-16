package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.io.IOException;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 使用独立 H2 与模拟微信 HTTP 响应，验证身份绑定和真实会话，不调用微信或运行库。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:mini_wechat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.sql.init.mode=always", "app.mock-providers=true", "app.redis-enabled=false",
    "app.admin-password=MiniWechatTest2026!", "app.storage=./target/mini-wechat-files"})
@ActiveProfiles("test")
class WechatMiniLoginTest {
  @Autowired WechatMiniLoginService service;
  @Autowired AuthService auth;
  @Autowired BusinessRepository store;
  @Autowired org.springframework.web.context.WebApplicationContext context;
  HttpClient client;
  MockedStatic<ProviderSupport> env;
  String openid;

  @BeforeEach
  void setup() throws Exception {
    env = mockStatic(ProviderSupport.class);
    env.when(() -> ProviderSupport.env(anyString())).thenReturn("");
    env.when(() -> ProviderSupport.env("PAW_WECHAT_MINI_APP_ID")).thenReturn("test-mini");
    env.when(() -> ProviderSupport.env("PAW_WECHAT_MINI_APP_SECRET")).thenReturn("test-secret");
    client = mock(HttpClient.class);
    Object target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
    ReflectionTestUtils.setField(target, "client", client);
    openid = UUID.randomUUID().toString();
    respond(200, write(map("openid", openid, "session_key", "private-session-key")));
  }

  @AfterEach
  void cleanup() { env.close(); }

  @SuppressWarnings("unchecked")
  void respond(int status, String body) throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
  }

  @Test
  void repeatedIdentityUsesSameAccountAndOnlyOwnRevocableTokenIsReturned() throws Exception {
    int usersBefore = store.list("user").size();
    var first = service.login(map("code", "first-code"), openid);
    var next = service.login(map("code", "new-code"), openid);
    assertEquals(true, first.get("isNewUser"));
    assertEquals(false, next.get("isNewUser"));
    assertEquals(usersBefore + 1, store.list("user").size());
    String id = text(object(first, "user"), "id");
    assertEquals(id, object(next, "user").get("id"));
    assertNotEquals(first.get("accessToken"), next.get("accessToken"));
    assertNull(object(first, "user").get("phoneMasked"));
    assertEquals("微信用户", object(first, "user").get("nickname"));
    assertNull(object(first, "user").get("avatarUrl"));
    assertNull(store.get("user", id).get("phone"));
    assertFalse(write(first).contains("private-session-key"));
    assertFalse(first.containsKey("openid"));
    var actor = auth.identify("Bearer " + first.get("accessToken"));
    assertEquals(id, auth.profile(actor).get("id"));
    auth.logout(actor);
    assertEquals(401, assertThrows(ApiException.class,
        () -> auth.identify("Bearer " + first.get("accessToken"))).status);
    assertEquals(id, auth.identify("Bearer " + next.get("accessToken")).id());
    // 必须向固定官方端点换取身份；不能把前端 code 直接当用户标识。
    var capture = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(2)).send(capture.capture(), any(HttpResponse.BodyHandler.class));
    var uri = capture.getValue().uri();
    assertEquals("https", uri.getScheme()); assertEquals("api.weixin.qq.com", uri.getHost());
    assertEquals("/sns/jscode2session", uri.getPath());
    assertTrue(uri.getQuery().contains("js_code=new-code"));
    assertTrue(uri.getQuery().contains("appid=test-mini"));
    assertTrue(uri.getQuery().contains("secret=test-secret"));
    assertTrue(uri.getQuery().contains("grant_type=authorization_code"));
    assertEquals(id, store.byKey("wechat_openid", "test-mini:" + openid).get("ownerId"));
  }

  @Test
  void providerBusyRateLimitAndConfigurationErrorsAreNotReportedAsExpiredCodes() throws Exception {
    int users = store.list("user").size(), sessions = store.list("session").size();
    // 微信业务错误常以 HTTP 200 返回，不能只看 HTTP 状态或统一提示 code 过期。
    for (int[] item : new int[][] {{-1, 502}, {45011, 429}, {40013, 503}, {40125, 503}, {40029, 401}, {40163, 401}, {40226, 401}, {99999, 502}}) {
      respond(200, write(map("errcode", item[0], "errmsg", "provider-private-detail")));
      var failure = assertThrows(ApiException.class, () -> service.login(map("code", "test-code"), UUID.randomUUID().toString()));
      assertEquals(item[1], failure.status);
      assertFalse(failure.getMessage().contains("provider-private-detail"));
    }
    assertEquals(users, store.list("user").size());
    assertEquals(sessions, store.list("session").size());
  }

  @Test
  void differentWechatIdentitiesAndApplicationsAreIsolatedWithoutPhones() throws Exception {
    var first = service.login(map("code", "one"), openid);
    respond(200, write(map("openid", UUID.randomUUID().toString(), "session_key", "private")));
    var other = service.login(map("code", "two"), openid);
    assertNotEquals(object(first, "user").get("id"), object(other, "user").get("id"));
    respond(200, write(map("openid", openid, "session_key", "private")));
    env.when(() -> ProviderSupport.env("PAW_WECHAT_MINI_APP_ID")).thenReturn("another-mini");
    var anotherApp = service.login(map("code", "three"), openid);
    assertNotEquals(object(first, "user").get("id"), object(anotherApp, "user").get("id"));
  }

  @Test
  void invalidCodeMissingConfigAndUpstreamFailuresCreateNoAccountOrSession() throws Exception {
    int users = store.list("user").size(), sessions = store.list("session").size();
    respond(200, "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
    assertEquals(401, assertThrows(ApiException.class, () -> service.login(map("code", "bad"), openid)).status);
    respond(200, "{\"openid\":\"untrusted-without-session-key\"}");
    assertEquals(401, assertThrows(ApiException.class, () -> service.login(map("code", "bad"), openid)).status);
    respond(503, "unavailable");
    assertEquals(502, assertThrows(ApiException.class, () -> service.login(map("code", "bad"), openid)).status);
    respond(200, "not-json");
    assertEquals(502, assertThrows(ApiException.class, () -> service.login(map("code", "bad"), openid)).status);
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("private URL"));
    var failure = assertThrows(ApiException.class, () -> service.login(map("code", "bad"), openid));
    assertEquals(502, failure.status); assertFalse(failure.getMessage().contains("private URL"));
    env.when(() -> ProviderSupport.env("PAW_WECHAT_MINI_APP_SECRET")).thenReturn("");
    assertEquals(503, assertThrows(ApiException.class, () -> service.login(map("code", "bad"), openid)).status);
    assertEquals(users, store.list("user").size()); assertEquals(sessions, store.list("session").size());
  }

  @Test
  void httpAcceptsOnlyCodeAndRejectsClientSuppliedIdentity() throws Exception {
    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
    mvc.perform(post("/api/v1/auth/mini-wechat/login").contentType("application/json")
        .content("{\"code\":\"valid\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isString())
        .andExpect(jsonPath("$.data.isNewUser").value(true))
        .andExpect(jsonPath("$.data.user.phoneMasked").isEmpty())
        .andExpect(jsonPath("$.data.session_key").doesNotExist());
    for (String body : List.of("{}", "{\"code\":\"\"}", "{\"code\":\"valid\",\"openid\":\"forged\"}",
        "{\"code\":\"valid\",\"phone\":\"13800008000\"}"))
      mvc.perform(post("/api/v1/auth/mini-wechat/login").contentType("application/json").content(body))
          .andExpect(status().isBadRequest());
    verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }
}
