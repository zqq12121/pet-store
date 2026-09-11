package com.warmpaw.application;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 协议用例：统一控制对外字段，保留已发布版本的业务规则。 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class AgreementApplicationService {
  private final BusinessRepository store;
  private final CatalogService catalog;

  public AgreementApplicationService(BusinessRepository store, CatalogService catalog) {
    this.store = store;
    this.catalog = catalog;
  }

  public OperationResult current(Map<String, Object> query) {
    return new OperationResult(
        200,
        agreementDto(
            catalog.currentAgreement(
                new Input(query, "type").choice("type", "live_pet_trade,pickup_confirmation"))));
  }

  public OperationResult list(Map<String, Object> query) {
    new Input(query, "type,page,pageSize");
    return new OperationResult(
        200,
        CatalogService.page(
            store.list("agreement").stream()
                .filter(
                    item ->
                        !query.containsKey("type")
                            || Objects.equals(item.get("type"), query.get("type")))
                .map(this::agreementDto)
                .toList(),
            query));
  }

  public OperationResult detail(String agreementId) {
    return new OperationResult(200, agreementDto(store.get("agreement", agreementId)));
  }

  public OperationResult create(Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(201, agreementDto(catalog.createAgreement(body, actor.id())));
  }

  public OperationResult publish(
      String agreementId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(
        200, agreementDto(catalog.publishAgreement(agreementId, body, actor.id())));
  }

  private Map<String, Object> agreementDto(Map<String, Object> a) {
    return CatalogService.select(
        a,
        "id,type,version,title,content,contentFormat,contentHash,publishedAt,healthGuaranteeDays,status,createdAt");
  }
}
