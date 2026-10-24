package com.warmpaw.application;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 协议用例：对外展示与后台维护。
 *
 * <p><b>所有出口都经过 {@link #agreementDto} 裁剪</b>，这是本类存在的首要理由。
 * 协议记录里除了正文还有 {@code ownerId}、内部状态流转等字段，直接下发会泄露运营信息；
 * 更重要的是<b>必须保证 {@code contentHash} 一并下发</b>——前端签署时要把
 * {@code version + contentHash} 回传，服务端据此确认"用户签的就是这份文本"。
 *
 * <p>协议有三种状态：{@code draft}（草稿，仅后台可见）、{@code published}（当前生效）、
 * {@code retired}（历史版本，永久固化）。同类型同时只有一个 {@code published}，
 * 由 {@link CatalogService#publishAgreement} 保证。
 */
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

  /**
   * 取当前生效的协议（按类型）。
   *
   * <p>只有这两种类型合法：{@code live_pet_trade}（活体交易主协议）与
   * {@code pickup_confirmation}（现场确认文本）。类型由必填参数给出，
   * 不提供默认值——默认成主协议会让"确认文本"这类调用悄悄拿到错误内容。
   */
  public OperationResult current(Map<String, Object> query) {
    return new OperationResult(
        200,
        agreementDto(
            catalog.currentAgreement(
                new Input(query, "type").choice("type", "live_pet_trade,pickup_confirmation"))));
  }

  /**
   * 协议列表（分页），可按类型筛选。
   *
   * <p>把三类状态都列出来是刻意的：后台需要看到草稿才能编辑，也需要看到历史版本
   * 才能回答"这单当时签的是哪一版"。筛选在内存完成，单店协议数量很小。
   */
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

  /** 按 id 取协议全文（含历史版本），用于订单详情页回看签署内容。 */
  public OperationResult detail(String agreementId) {
    return new OperationResult(200, agreementDto(store.get("agreement", agreementId)));
  }

  /** 新建协议草稿；版本号在同类型下必须唯一，重复返回 409。 */
  public OperationResult create(Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(201, agreementDto(catalog.createAgreement(body, actor.id())));
  }

  /** 发布协议：同类型旧版本自动退役，且退役后不可再发布。 */
  public OperationResult publish(
      String agreementId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(
        200, agreementDto(catalog.publishAgreement(agreementId, body, actor.id())));
  }

  /**
   * 协议对外投影。
   *
   * <p>包含 {@code content} 全文与 {@code contentHash}：前者让用户能看到要签什么，
   * 后者用于签署时的一致性校验。不含 {@code ownerId} 与任何内部审计字段。
   */
  private Map<String, Object> agreementDto(Map<String, Object> a) {
    return CatalogService.select(
        a,
        "id,type,version,title,content,contentFormat,contentHash,publishedAt,healthGuaranteeDays,status,createdAt");
  }
}
