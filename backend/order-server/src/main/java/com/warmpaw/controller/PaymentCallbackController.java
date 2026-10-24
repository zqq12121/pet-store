package com.warmpaw.controller;

import static com.warmpaw.common.Json.map;

import com.warmpaw.common.ApiException;
import com.warmpaw.service.PaymentCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * 微信支付回调入口：接收支付结果与退款结果通知。
 *
 * <p><b>调用方</b>是微信支付平台服务器，不是浏览器。路由前缀 {@code /api/v1/callbacks/wechat-pay}，
 * 由 order-server 直接暴露，网关不对前端开放这条路径。
 *
 * <p><b>权限模型</b>：完全公开，不调用 {@code AuthService.role} 也不调用 {@code staff}——
 * 回调方没有登录态。身份靠微信的四个签名头（{@code Wechatpay-Timestamp/Nonce/Serial/Signature}）
 * 在 {@link PaymentCallbackService#handle} 内验签确定，验签失败即拒收。{@code kind} 路径参数只允许
 * {@code payments} / {@code refunds}，其余按 404 处理，避免探测出额外的通知通道。
 *
 * <p><b>响应格式</b>：微信支付通知保持原始报文和平台规定响应，不套用买家接口响应包装。
 * 成功返回空 200，失败返回微信要求的 {@code code=FAIL} 报文，微信据此决定是否重投。
 *
 * <p>业务全部委托给 {@link PaymentCallbackService}，本类只做"取原始报文 + 取签名头 + 翻译异常"。
 */
@RestController
@RequestMapping("/api/v1/callbacks/wechat-pay")
public class PaymentCallbackController {
  private final PaymentCallbackService callbacks;

  public PaymentCallbackController(PaymentCallbackService callbacks) {
    this.callbacks = callbacks;
  }

  /**
   * 接收一条支付/退款结果通知。
   *
   * <p>{@code raw} 必须是原始字符串而不是 {@code Map}：签名覆盖的是报文字节，先反序列化再验签会因
   * 字段顺序、转义、空白被重写而验签失败，所以这里只能收 {@code String} 再交给 service 解析。
   *
   * <p><b>幂等</b>：不使用 {@code Idempotency-Key}（微信不会带）。重复投递由 service 按
   * {@code out_trade_no} / {@code out_refund_no} 与平台流水号去重，同一通知到达多次只入账一次。
   *
   * <p><b>状态码</b>：成功固定 200——回调不是资源创建，没有 201 语义；也从不返回 202，
   * 因为返回 200 之前入账已经完成。失败时用 {@code ApiException.status}（验签失败、金额不符、
   * 找不到本地单等 4xx），非预期异常兜底 500。任何非 200 都会触发微信重投，
   * 所以"处理失败"必须如实返回，不能吞异常假装成功。
   */
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
