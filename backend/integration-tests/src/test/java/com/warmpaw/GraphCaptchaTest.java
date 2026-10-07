package com.warmpaw;

import static com.warmpaw.common.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.warmpaw.common.*;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.*;

/** 截获真实 HTTP 请求核实签名和表单编码；隔离云端，不发送短信、不触碰运行库。 */
class GraphCaptchaTest {
  private Map<String, Object> proof() {
    return map("lot_number", "test-lot", "captcha_output", "output+ /&=中文",
        "pass_token", "test-token", "gen_time", "1791240000");
  }

  private MockedStatic<ProviderSupport> configured() {
    var env = mockStatic(ProviderSupport.class, CALLS_REAL_METHODS);
    env.when(() -> ProviderSupport.env(anyString())).thenReturn("");
    env.when(() -> ProviderSupport.env("PAW_CAPTCHA_APP_ID")).thenReturn("test-id");
    env.when(() -> ProviderSupport.env("PAW_CAPTCHA_APP_KEY")).thenReturn("test-key");
    return env;
  }

  private Map<String, String> form(HttpRequest request) {
    var body = new StringBuilder();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
      public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
      public void onNext(ByteBuffer buffer) { body.append(StandardCharsets.UTF_8.decode(buffer)); }
      public void onError(Throwable error) { fail(error); }
      public void onComplete() {}
    });
    Map<String, String> values = new HashMap<>();
    for (String pair : body.toString().split("&")) {
      String[] parts = pair.split("=", 2);
      values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return values;
  }

  @Test void successfulProofUsesHmacAndFormEncodingAndCannotBeReplayed() throws Exception {
    var service = new GraphCaptchaService(new TemporaryStore(null, false));
    HttpClient client = mock(HttpClient.class);
    HttpClient.Builder builder = mock(HttpClient.Builder.class, RETURNS_SELF);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(builder.build()).thenReturn(client);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"status\":\"success\",\"result\":\"success\"}");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    try (var env = configured(); var http = mockStatic(HttpClient.class)) {
      http.when(HttpClient::newBuilder).thenReturn(builder);
      assertEquals(map("captchaId", "test-id"), service.config());
      service.verify(proof());
      assertThrows(ApiException.class, () -> service.verify(proof()));
      var captured = ArgumentCaptor.forClass(HttpRequest.class);
      verify(client, times(1)).send(captured.capture(), any(HttpResponse.BodyHandler.class));
      var request = captured.getValue();
      assertEquals("POST", request.method());
      assertEquals("https://captcha.alicaptcha.com/validate?captcha_id=test-id", request.uri().toString());
      assertEquals("application/x-www-form-urlencoded", request.headers().firstValue("Content-Type").orElseThrow());
      var values = form(request);
      assertEquals(proof().get("captcha_output"), values.get("captcha_output"));
      assertEquals("1791240000", values.get("gen_time"));
      assertEquals("46ca4316cc123583eb7ab61a61b32de5757609858db24fad6fc75fb052cda66c", values.get("sign_token"));
      assertFalse(values.containsKey("appKey"));
      assertEquals(5, values.size());
    }
  }

  @Test void missingConfigurationAndIncompleteProofDoNotReachCloud() {
    var service = new GraphCaptchaService(new TemporaryStore(null, false));
    try (var env = configured(); var http = mockStatic(HttpClient.class)) {
      for (String field : List.of("lot_number", "captcha_output", "pass_token", "gen_time")) {
        var missing = proof();missing.remove(field);
        assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class, () -> service.verify(missing)).code);
      }
      env.when(() -> ProviderSupport.env("PAW_CAPTCHA_APP_KEY")).thenReturn("");
      assertEquals(503, assertThrows(ApiException.class, service::config).status);
      assertThrows(ApiException.class, () -> service.verify(proof()));
      http.verifyNoInteractions();
    }
  }

  @Test void failedExpiredMalformedAndHttpErrorResponsesNeverPass() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpClient.Builder builder = mock(HttpClient.Builder.class, RETURNS_SELF);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(builder.build()).thenReturn(client);
    when(response.statusCode()).thenReturn(200);
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    try (var env = configured(); var http = mockStatic(HttpClient.class)) {
      http.when(HttpClient::newBuilder).thenReturn(builder);
      for (String body : List.of("{\"status\":\"success\",\"result\":\"fail\",\"reason\":\"pass_token expire\"}",
          "{\"status\":\"error\",\"result\":\"success\"}", "{}", "invalid json")) {
        when(response.body()).thenReturn(body);
        assertThrows(ApiException.class, () -> new GraphCaptchaService(new TemporaryStore(null, false)).verify(proof()));
      }
      when(response.statusCode()).thenReturn(503);
      assertEquals(502, assertThrows(ApiException.class, () ->
          new GraphCaptchaService(new TemporaryStore(null, false)).verify(proof())).status);
      when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("test timeout"));
      assertEquals(502, assertThrows(ApiException.class, () ->
          new GraphCaptchaService(new TemporaryStore(null, false)).verify(proof())).status);
    }
  }

  @Test void smsAndPasswordEndpointsCannotBypassFailedGraphVerification() {
    var store = mock(BusinessRepository.class);
    var sms = mock(SmsGateway.class);
    var graph = mock(GraphCaptchaService.class);
    var auth = new AuthService(store, new TemporaryStore(null, false), sms, graph, false);
    doThrow(new ApiException(400, "CAPTCHA_INVALID", "图形验证失败")).when(graph).verify(anyMap());
    for (String purpose : List.of("login", "wechat_bind", "password_reset")) {
      assertThrows(ApiException.class, () -> auth.sendLoginSms(map("phone", "13900000000", "purpose", purpose), "test"));
    }
    assertThrows(ApiException.class, () -> auth.passwordLogin(map("account", "alice", "password", "TestPassword123"), "test"));
    assertThrows(ApiException.class, () -> auth.adminLogin(map("username", "admin", "password", "TestPassword123"), "test"));
    verify(graph, times(5)).verify(anyMap());
    verifyNoInteractions(sms, store);
    // 旧图片验证码参数不能继续成为绕过新验证的入口。
    assertThrows(ApiException.class, () -> auth.sendLoginSms(map("phone", "13900000000", "purpose", "login",
        "captchaId", "old", "captchaCode", "1234"), "test"));
    // 管理端必须拒绝旧图片凭据，不能回退到历史登录契约。
    assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class, () -> auth.adminLogin(
        map("username", "admin", "password", "TestPassword123", "captchaId", "old", "captchaCode", "1234"), "test")).code);
    verify(graph, times(5)).verify(anyMap());
    verifyNoInteractions(sms, store);
  }

  @Test void wechatAuthorizationIsIssuedOnlyAfterVerificationAndStateCannotBeForged() {
    var temp = new TemporaryStore(null, false);
    var auth = mock(AuthService.class);
    var graph = mock(GraphCaptchaService.class);
    var service = new WechatLoginService(mock(BusinessRepository.class), temp, auth, graph);
    try (var env = configured()) {
      env.when(() -> ProviderSupport.env("PAW_WECHAT_APP_ID")).thenReturn("wechat-app");
      env.when(() -> ProviderSupport.env("PAW_WECHAT_APP_SECRET")).thenReturn("wechat-secret");
      env.when(() -> ProviderSupport.env("PAW_WECHAT_OAUTH_REDIRECT")).thenReturn("https://shop.example.test/login");
      doThrow(new ApiException(400, "CAPTCHA_INVALID", "图形验证失败")).when(graph).verify(anyMap());
      assertThrows(ApiException.class, () -> service.authorize(map("scene", "login", "returnPath", "/"), null, "browser"));
      verify(auth, never()).randomToken();
      doNothing().when(graph).verify(anyMap());
      when(auth.randomToken()).thenReturn("verified-state");
      var result = service.authorize(map("scene", "login", "returnPath", "/"), null, "browser");
      assertTrue(text(result, "authorizeUrl").contains("state=verified-state"));
      assertEquals(hash("browser"), read(temp.get("oauth:verified-state")).get("browser"));
      assertThrows(ApiException.class, () -> service.login(map("code", "forged", "state", "forged-state"), null, "browser"));
    }
  }
}
