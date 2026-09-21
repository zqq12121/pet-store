package com.warmpaw;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.warmpaw.common.ProviderSupport;
import com.warmpaw.service.WechatLoginService;
import org.junit.jupiter.api.Test;

/** 微信配置不完整或回调不安全时，不能向顾客展示可用的快捷登录。 */
class WechatCapabilityTest {
  @Test
  void requiresCompleteCredentialsAndHttpsCallback() {
    var service = new WechatLoginService(null, null, null);
    try (var env = mockStatic(ProviderSupport.class)) {
      env.when(() -> ProviderSupport.env(anyString())).thenReturn("");
      assertFalse(service.configured());
      env.when(() -> ProviderSupport.env("PAW_WECHAT_APP_ID")).thenReturn("test-app");
      env.when(() -> ProviderSupport.env("PAW_WECHAT_OAUTH_REDIRECT")).thenReturn("https://shop.example.test/login");
      assertFalse(service.configured());
      env.when(() -> ProviderSupport.env("PAW_WECHAT_APP_SECRET")).thenReturn("test-secret");
      assertTrue(service.configured());
      for (String callback : new String[] {"http://shop.example.test/login", "https://", "https://user@shop.example.test/login", "bad callback"}) {
        env.when(() -> ProviderSupport.env("PAW_WECHAT_OAUTH_REDIRECT")).thenReturn(callback);
        assertFalse(service.configured());
      }
    }
  }
}
