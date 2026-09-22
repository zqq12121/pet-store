package com.warmpaw;

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
import org.mockito.ArgumentCaptor;

/** 通过截获 HTTP 请求验证真实协议，不调用云端、不发送短信。 */
class PnvsSmsTest {
  private Map<String, String> sentRequest(String purpose, boolean success) throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpClient.Builder builder = mock(HttpClient.Builder.class, RETURNS_SELF);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(builder.build()).thenReturn(client);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"Code\":\"OK\",\"Success\":" + success + "}");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    try (var http = mockStatic(HttpClient.class); var env = mockStatic(ProviderSupport.class)) {
      http.when(HttpClient::newBuilder).thenReturn(builder);
      env.when(() -> ProviderSupport.env(anyString())).thenReturn("");
      env.when(() -> ProviderSupport.env("OSS_ACCESS_KEY_ID")).thenReturn("test-id");
      env.when(() -> ProviderSupport.env("OSS_ACCESS_KEY_SECRET")).thenReturn("test-secret");
      SmsGateway gateway = new SmsGateway();
      if (success) gateway.send("13900000000", "123456", purpose);
      else assertThrows(ApiException.class, () -> gateway.send("13900000000", "123456", purpose));
      var captured = ArgumentCaptor.forClass(HttpRequest.class);
      verify(client).send(captured.capture(), any(HttpResponse.BodyHandler.class));
      HttpRequest request = captured.getValue();
      assertEquals("https://dypnsapi.aliyuncs.com/", request.uri().toString());
      StringBuilder body = new StringBuilder();
      request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
        public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        public void onNext(ByteBuffer buffer) { body.append(StandardCharsets.UTF_8.decode(buffer)); }
        public void onError(Throwable error) { fail(error); }
        public void onComplete() {}
      });
      Map<String, String> params = new HashMap<>();
      for (String pair : body.toString().split("&")) {
        String[] parts = pair.split("=", 2);
        params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
      }
      return params;
    }
  }

  @Test void loginAndBindingUseGiftTemplateAndOwnCode() throws Exception {
    for (String purpose : List.of("login", "wechat_bind", "password_reset")) {
      var params = sentRequest(purpose, true);
      assertEquals("SendSmsVerifyCode", params.get("Action"));
      assertEquals("13900000000", params.get("PhoneNumber"));
      assertFalse(params.containsKey("PhoneNumbers"));
      assertEquals("恒创联众", params.get("SignName"));
      assertEquals("100001", params.get("TemplateCode"));
      assertEquals(Map.of("code", "123456", "min", "5"), Json.read(params.get("TemplateParam")));
      assertEquals("false", params.get("ReturnVerifyCode"));
      assertEquals("test-id", params.get("AccessKeyId"));
      assertFalse(params.get("Signature").isBlank());
    }
  }

  @Test void successFlagMustBeTrue() throws Exception { sentRequest("login", false); }

  @Test void incompleteDedicatedCredentialsDoNotMixWithOssCredentials() {
    try (var env = mockStatic(ProviderSupport.class)) {
      env.when(() -> ProviderSupport.env(anyString())).thenReturn("");
      env.when(() -> ProviderSupport.env("PAW_SMS_ACCESS_KEY_ID")).thenReturn("dedicated-id");
      env.when(() -> ProviderSupport.env("OSS_ACCESS_KEY_SECRET")).thenReturn("other-secret");
      assertThrows(ApiException.class, () -> new SmsGateway().send("13900000000", "123456", "login"));
    }
  }

  @Test void failedDeliveryDoesNotSaveCode() {
    TemporaryStore temp = mock(TemporaryStore.class);
    SmsGateway gateway = mock(SmsGateway.class);
    doThrow(new ApiException(502, "UPSTREAM_ERROR", "发送失败")).when(gateway).send(anyString(), anyString(), anyString());
    AuthService auth = new AuthService(mock(BusinessRepository.class), temp, gateway, false);
    assertThrows(ApiException.class, () -> auth.sendSms("13900000000", "login", "", "test"));
    verify(temp, never()).put(startsWith("sms:"), anyString(), anyInt());
  }

  @Test void acceptedCodeIsPhoneBoundRateLimitedAndConsumedOnce() {
    TemporaryStore temp = new TemporaryStore(null, false);
    SmsGateway gateway = mock(SmsGateway.class);
    AuthService auth = new AuthService(mock(BusinessRepository.class), temp, gateway, false);
    var result = auth.sendSms("13900000000", "login", "", "test");
    assertEquals("accepted", result.get("status"));
    assertFalse(result.containsKey("code"));
    var code = ArgumentCaptor.forClass(String.class);
    verify(gateway).send(eq("13900000000"), code.capture(), eq("login"));
    String request = Json.text(result, "smsRequestId");
    assertThrows(ApiException.class, () -> auth.checkSms(request, code.getValue(), "13800000000", "login", ""));
    assertThrows(ApiException.class, () -> auth.checkSms(request, code.getValue(), "13900000000", "wechat_bind", ""));
    auth.checkSms(request, code.getValue(), "13900000000", "login", "");
    assertThrows(ApiException.class, () -> auth.checkSms(request, code.getValue(), "13900000000", "login", ""));
    assertThrows(ApiException.class, () -> auth.sendSms("13900000000", "login", "", "test"));
  }
}
