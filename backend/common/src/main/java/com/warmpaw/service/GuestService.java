package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 游客身份仅为访问统计提供去重标识，不创建 AI 会话。 */
@Service
public class GuestService {
  private final TemporaryStore temp;
  private final AuthService auth;

  public GuestService(TemporaryStore temp, AuthService auth) {
    this.temp = temp;
    this.auth = auth;
  }

  public Map<String, Object> create(String ip) {
    temp.limit("guest:" + ip, 20, 3600);
    String token = auth.randomToken(), id = com.warmpaw.common.Json.id("guest");
    temp.put("guest:" + hash(token), id, 86400);
    return map("guestToken", token, "expiresAt", Instant.now().plusSeconds(86400).toString());
  }

  public AuthService.Actor identify(String token) {
    require(token != null, 401, "UNAUTHORIZED", "缺少游客身份");
    String id = temp.get("guest:" + hash(token));
    require(id != null, 401, "TOKEN_EXPIRED", "游客身份已过期");
    return new AuthService.Actor(id, "guest", null, hash(token));
  }
}
