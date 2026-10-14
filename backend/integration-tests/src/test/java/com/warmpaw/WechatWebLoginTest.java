package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实 H2 账号/会话配合模拟微信响应，不调用微信，也不访问运行中的数据库。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:web_wechat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.sql.init.mode=always", "app.mock-providers=true", "app.redis-enabled=false",
    "app.admin-password=WebWechatTest2026!", "app.storage=./target/web-wechat-files"})
@ActiveProfiles("test")
class WechatWebLoginTest {
  @Autowired WechatLoginService service;
  @Autowired AuthService auth;
  @Autowired BusinessRepository store;
  @Autowired TemporaryStore temp;
  @Autowired org.springframework.web.context.WebApplicationContext context;
  @MockitoBean GraphCaptchaService graph;
  MockedStatic<ProviderSupport> env;
  HttpClient client;
  String openid;

  @BeforeEach
  void setup() throws Exception {
    env = mockStatic(ProviderSupport.class, CALLS_REAL_METHODS);
    env.when(() -> ProviderSupport.env(anyString())).thenReturn("");
    env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_APP_ID")).thenReturn("web-app");
    env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_APP_SECRET")).thenReturn("web-secret");
    env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_REDIRECT")).thenReturn("https://shop.example.test/login");
    client = mock(HttpClient.class);
    openid = UUID.randomUUID().toString();
  }

  @AfterEach
  void cleanup() { env.close(); }

  String authorize(String browser) {
    var result = service.authorize(map("mode", "web", "scene", "login", "returnPath", "/orders"), null, browser);
    var uri = URI.create(text(result, "authorizeUrl"));
    assertEquals("open.weixin.qq.com", uri.getHost());
    assertEquals("/connect/qrconnect", uri.getPath());
    assertTrue(uri.getQuery().contains("scope=snsapi_login"));
    assertTrue(uri.getQuery().contains("appid=web-app"));
    assertFalse(write(result).contains("web-secret"));
    return Arrays.stream(uri.getRawQuery().split("&")).filter(p -> p.startsWith("state="))
        .map(p -> URLDecoder.decode(p.substring(6), StandardCharsets.UTF_8)).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  void respond(int status, String body) throws Exception {
    Object target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
    ReflectionTestUtils.setField(target, "client", client);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
  }

  Map<String, Object> login() {
    return service.login(map("code", "one-time-code", "state", authorize("browser")), null, "browser");
  }

  @Test
  void firstScanCreatesPhoneFreeAccountAndRepeatScanReusesIt() throws Exception {
    respond(200, write(map("openid", openid, "access_token", "upstream-private-token")));
    var first = object(login(), "login");
    var again = object(login(), "login");
    String id = text(object(first, "user"), "id");
    assertEquals(id, object(again, "user").get("id"));
    assertNull(store.get("user", id).get("phone"));
    assertFalse(write(first).contains("upstream-private-token"));
    assertFalse(first.containsKey("openid"));
    assertEquals(id, store.byKey("wechat_openid", "web-app:" + openid).get("ownerId"));
    assertNotEquals(first.get("accessToken"), again.get("accessToken"));
    var actor = auth.identify("Bearer " + first.get("accessToken"));
    assertEquals(id, actor.id());
    auth.logout(actor);
    assertEquals(401, assertThrows(ApiException.class, () -> auth.identify("Bearer " + first.get("accessToken"))).status);
    assertEquals(id, auth.identify("Bearer " + again.get("accessToken")).id());
    var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
    assertEquals("api.weixin.qq.com", requests.getValue().uri().getHost());
    assertTrue(requests.getValue().uri().getQuery().contains("appid=web-app&secret=web-secret"));
  }

  @Test
  void differentIdentitiesAndApplicationIdsStayIsolated() throws Exception {
    respond(200, write(map("openid", openid)));
    String first = text(object(object(login(), "login"), "user"), "id");
    respond(200, write(map("openid", UUID.randomUUID().toString())));
    assertNotEquals(first, object(object(login(), "login"), "user").get("id"));
    respond(200, write(map("openid", openid)));
    env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_APP_ID")).thenReturn("another-web-app");
    var result = service.authorize(map("mode", "web", "scene", "login", "returnPath", "/"), null, "browser");
    String state = URI.create(text(result, "authorizeUrl")).getQuery().split("state=")[1].split("&")[0];
    var other = service.login(map("code", "next", "state", state), null, "browser");
    assertNotEquals(first, object(object(other, "login"), "user").get("id"));
  }

  @Test
  void stateIsBrowserBoundSingleUseAndExpires() throws Exception {
    respond(200, write(map("openid", openid)));
    String wrong = authorize("original-browser");
    assertEquals("WECHAT_AUTH_INVALID", assertThrows(ApiException.class,
        () -> service.login(map("code", "code", "state", wrong), null, "different-browser")).code);
    String valid = authorize("browser");
    service.login(map("code", "code", "state", valid), null, "browser");
    String expired = authorize("browser");
    temp.put("oauth:" + expired, temp.get("oauth:" + expired), -1);
    for (String state : List.of(valid, expired, "forged-state"))
      assertEquals("WECHAT_AUTH_INVALID", assertThrows(ApiException.class,
          () -> service.login(map("code", "code", "state", state), null, "browser")).code);
    verify(client, times(1)).send(any(), any(HttpResponse.BodyHandler.class));
  }

  @Test
  void callbackCannotSwitchModeApplicationOrReturnPath() throws Exception {
    respond(200, write(map("openid", openid)));
    String state = authorize("browser");
    assertThrows(ApiException.class, () -> service.login(map("code", "code", "state", state, "mode", "oauth"), null, "browser"));
    env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_APP_ID")).thenReturn("changed-app");
    assertEquals("WECHAT_AUTH_INVALID", assertThrows(ApiException.class,
        () -> service.login(map("code", "code", "state", state), null, "browser")).code);
    verifyNoInteractions(client);
  }

  @Test
  void missingConfigUnsafeRedirectAndFailedCaptchaNeverIssueAuthorization() {
    env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_APP_SECRET")).thenReturn("");
    assertEquals(503, assertThrows(ApiException.class, () -> authorize("browser")).status);
    env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_APP_SECRET")).thenReturn("web-secret");
    for (String callback : List.of("http://shop.example.test/login", "https://user@shop.example.test/login", "invalid")) {
      env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_REDIRECT")).thenReturn(callback);
      assertEquals(503, assertThrows(ApiException.class, () -> authorize("browser")).status);
    }
    env.when(() -> ProviderSupport.env("PAW_WECHAT_WEB_REDIRECT")).thenReturn("https://shop.example.test/login");
    doThrow(new ApiException(400, "CAPTCHA_INVALID", "图形验证失败")).when(graph).verify(anyMap());
    assertEquals("CAPTCHA_INVALID", assertThrows(ApiException.class, () -> authorize("browser")).code);
    assertThrows(ApiException.class, () -> service.authorize(map("mode", "web", "scene", "login", "returnPath", "//evil.test"), null, "browser"));
  }

  @Test
  void upstreamFailuresDoNotCreateAccounts() throws Exception {
    int users = store.list("user").size();
    for (String body : List.of("{\"errcode\":40029}", "{\"openid\":\"\"}", "{}", "not-json")) {
      respond(200, body);
      assertThrows(ApiException.class, this::login);
    }
    respond(503, "upstream unavailable");
    assertThrows(ApiException.class, this::login);
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("private URL"));
    assertFalse(assertThrows(ApiException.class, this::login).getMessage().contains("private URL"));
    assertEquals(users, store.list("user").size());
  }

  @Test
  void httpCapabilityAndSecureCookieExposeNoSecrets() throws Exception {
    var mvc = MockMvcBuilders.webAppContextSetup(context).build();
    mvc.perform(get("/api/v1/auth/capabilities")).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.wechatQrLogin").value(true))
        .andExpect(jsonPath("$.data.wechatLogin").value(false));
    mvc.perform(post("/api/v1/auth/wechat/authorize-url").contentType("application/json")
        .content(write(map("mode", "web", "scene", "login", "returnPath", "/"))))
        .andExpect(status().isOk()).andExpect(cookie().httpOnly("paw_oauth", true))
        .andExpect(cookie().secure("paw_oauth", true)).andExpect(cookie().maxAge("paw_oauth", 300))
        .andExpect(jsonPath("$.data.expiresIn").value(300));
  }
}
