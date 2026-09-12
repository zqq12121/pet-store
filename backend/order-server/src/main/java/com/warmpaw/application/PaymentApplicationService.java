package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogReader;
import com.warmpaw.service.PaymentService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 支付用例：支付能力展示及受限的退款重试。 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class PaymentApplicationService {
  private final BusinessRepository store;
  private final PaymentService payments;
  private final AuthService auth;

  public PaymentApplicationService(
      BusinessRepository store, PaymentService payments, AuthService auth) {
    this.store = store;
    this.payments = payments;
    this.auth = auth;
  }

  public OperationResult capabilities() {
    return new OperationResult(200, payments.capabilities());
  }

  public OperationResult retryRefund(String refundId, Map<String, Object> body) {
    new Input(body, "reason").str("reason", 1, 500);
    Map<String, Object> refund = store.get("refund", refundId);
    BusinessRepository.state(refund, "failed");
    require(auth.local(), 409, "REFUND_IN_PROGRESS", "需先查询原退款并确认平台允许重试，不能盲目重发");
    refund.put("status", "processing");
    store.save(refund);
    return new OperationResult(202, refundDto(refund), "refund", refundId);
  }

  private Map<String, Object> refundDto(Map<String, Object> r) {
    return CatalogReader.select(
        r, "id,refundNo,afterSaleId,reason,amount,status,createdAt,succeededAt,failureReason");
  }
}
