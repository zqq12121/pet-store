package com.warmpaw.controller;

import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.service.WechatMiniLoginService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 原生小程序登录入口，身份只能由微信服务端校验。 */
@RestController
@RequestMapping("/api/v1/auth/mini-wechat")
public class WechatMiniAuthController {
  private final WechatMiniLoginService login;

  public WechatMiniAuthController(WechatMiniLoginService login) {
    this.login = login;
  }

  @PostMapping("/login")
  public ResponseEntity<Object> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(200, login.login(body, request.getRemoteAddr()));
  }
}
