package com.warmpaw.controller;

import static com.warmpaw.common.Json.map;

import com.warmpaw.common.ApiException;
import com.warmpaw.service.PaymentCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 微信支付通知保持原始报文和平台规定响应，不套用买家接口响应包装。 */
@RestController
@RequestMapping("/api/v1/callbacks/wechat-pay")
public class PaymentCallbackController {
  private final PaymentCallbackService callbacks;

  public PaymentCallbackController(PaymentCallbackService callbacks) {
    this.callbacks = callbacks;
  }

  @PostMapping(value = "/{kind}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> callback(
      @PathVariable String kind, @RequestBody String raw, HttpServletRequest request) {
    try {
      callbacks.handle(
          kind,
          raw,
          request.getHeader("Wechatpay-Timestamp"),
          request.getHeader("Wechatpay-Nonce"),
          request.getHeader("Wechatpay-Serial"),
          request.getHeader("Wechatpay-Signature"));
      return ResponseEntity.ok().build();
    } catch (Exception e) {
      return ResponseEntity.status(e instanceof ApiException a ? a.status : 500)
          .body(map("code", "FAIL", "message", "通知处理失败"));
    }
  }
}
