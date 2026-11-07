package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogReader;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 支付用例编排层：受限的退款重试。
 *
 * <p>真正的发起支付入口已下线（见 {@code OrderApplicationService#beginPayment}），
 * 这里只剩人工补救失败退款。
 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class PaymentApplicationService {
  private final BusinessRepository store;
  private final AuthService auth;

  public PaymentApplicationService(
      BusinessRepository store, AuthService auth) {
    this.store = store;
    this.auth = auth;
  }

  /**
   * 人工重试一笔失败的退款。
   *
   * <p><b>只允许在本地模式执行</b>。线上环境返回 409 并明确说明原因：
   * 微信退款的超时不等于失败，本地把 {@code failed} 改成 {@code processing} 再发一次，
   * 可能在平台侧产生第二笔退款（平台按 {@code out_refund_no} 幂等，但如果第一次实际没受理，
   * 盲目重发会掩盖真实的对账缺口）。线上正确做法是先按退款单号查询平台状态，再决定。
   *
   * <p>{@code reason} 必填，用于审计与人工追溯——重试资金操作必须能回答"谁、为什么"。
   */
  public OperationResult retryRefund(String refundId, Map<String, Object> body) {
    new Input(body, "reason").str("reason", 1, 500);
    Map<String, Object> refund = store.get("refund", refundId);
    BusinessRepository.state(refund, "failed");
    require(auth.local(), 409, "REFUND_IN_PROGRESS", "需先查询原退款并确认平台允许重试，不能盲目重发");
    refund.put("status", "processing");
    store.save(refund);
    return new OperationResult(202, refundDto(refund), "refund", refundId);
  }

  /** 退款单对外投影，隐藏 {@code attempted}/{@code providerAccepted} 等重试内部状态。 */
  private Map<String, Object> refundDto(Map<String, Object> r) {
    return CatalogReader.select(
        r, "id,refundNo,afterSaleId,reason,amount,status,createdAt,succeededAt,failureReason");
  }
}
