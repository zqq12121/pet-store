package com.warmpaw.controller;

import static com.warmpaw.controller.support.ApiResponses.ok;
import com.warmpaw.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 扫码与网站领取分离；含短期凭据的响应一律禁止缓存。 */
@RestController
@RequestMapping("/api/v1/auth/mini-wechat/qr")
public class WechatMiniQrController {
  private final WechatMiniQrService qr;
  private final AuthService auth;
  public WechatMiniQrController(WechatMiniQrService qr, AuthService auth) { this.qr = qr; this.auth = auth; }

  private ResponseEntity<Object> response(Object data) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ok(200, data).getBody());
  }
  @PostMapping
  public ResponseEntity<Object> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
    return response(qr.create(body, req.getRemoteAddr()));
  }
  @GetMapping("/{ticket}")
  public ResponseEntity<Object> inspect(@PathVariable String ticket, HttpServletRequest req) {
    return response(qr.inspect(ticket, req.getRemoteAddr()));
  }
  @PostMapping("/{ticket}/confirm")
  public ResponseEntity<Object> confirm(@PathVariable String ticket, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    return response(qr.confirm(ticket, body, auth.identify(req.getHeader("Authorization"))));
  }
  @PostMapping("/{ticket}/poll")
  public ResponseEntity<Object> poll(@PathVariable String ticket, @RequestBody Map<String, Object> body, HttpServletRequest req) {
    return response(qr.poll(ticket, body, req.getRemoteAddr()));
  }
  @PostMapping("/{ticket}/cancel")
  public ResponseEntity<Object> cancel(@PathVariable String ticket, @RequestBody Map<String, Object> body) {
    return response(qr.cancel(ticket, body));
  }
}
