package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AgreementApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台协议管理接口：仅适配 HTTP，具体业务在 AgreementApplicationService（调用方：店长）。
 *
 * <p>路由前缀 {@code /api/v1}，四组路径都在 {@code /admin/agreements} 下。
 * <b>权限模型：{@link AccessLevel#ADMIN}</b>，由 {@link ApiRequestExecutor} 统一执行
 * {@code AuthService.role(actor, "admin")}——员工令牌同样 403；写操作还会在取得全局写锁后
 * 再次 {@code requireActiveSession}，避免被停用的账号在等锁期间继续落库。
 *
 * <p>委托 {@link AgreementApplicationService}：协议是法律文本，创建后内容与哈希即固化，
 * 发布则把同类型旧版本转为 retired，本类不重复这些规则。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminAgreementController {
  private final ApiRequestExecutor requests;
  private final AgreementApplicationService service;

  public AdminAgreementController(
      ApiRequestExecutor requests, AgreementApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /admin/agreements；访问级别：ADMIN。
   *
   * <p>协议列表，可按 {@code type}（live_pet_trade / pickup_confirmation）过滤，
   * 并用 {@code page}/{@code pageSize} 分页。返回含草稿、已发布与已退役的全部版本，
   * 便于店长对照历史。只读、幂等。
   *
   * @param query 仅允许 type/page/pageSize，其它参数被 Input 白名单拒绝
   * @return 200 与分页后的协议摘要
   */
  @GetMapping("/admin/agreements")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.list(query));
  }

  /**
   * GET /admin/agreements/{agreementId}；访问级别：ADMIN。
   *
   * <p>单个协议全文，含 {@code contentHash}——它是订单签署时引用的指纹，
   * 店长可据此核对文本是否被改过。id 不存在时 404。只读、幂等。
   *
   * @return 200 与协议详情（不含内部审计字段）
   */
  @GetMapping("/admin/agreements/{agreementId}")
  public ResponseEntity<Object> detail(
      @PathVariable String agreementId, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.detail(agreementId));
  }

  /**
   * POST /admin/agreements；访问级别：ADMIN。
   *
   * <p>新建一份<b>草稿</b>协议，成功 201。{@code type=live_pet_trade} 可带
   * {@code healthGuaranteeDays}(1~30)，{@code pickup_confirmation} 带该字段会 400。
   * 幂等键是 {@code type:version}：同类型同版本号已存在返回 409 {@code VERSION_CONFLICT}
   * ——协议文本不允许被覆盖，否则已签署订单引用的内容会变。
   *
   * <p>创建时即计算 {@code contentHash}，之后不可变。请求体可省略（null 按空对象处理），
   * 但省略后必然因缺少 type/version/content 而 400。
   *
   * @return 201 与新建协议（{@code status=draft}）
   */
  @PostMapping("/admin/agreements")
  public ResponseEntity<Object> create(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request, body, AccessLevel.ADMIN, false, context -> service.create(body, context.actor()));
  }

  /**
   * POST /admin/agreements/{agreementId}/publish；访问级别：ADMIN。
   *
   * <p>把草稿发布为生效版本，成功 200。body 必须含 {@code reviewConfirmed=true}
   * （确认已人工复核文本），否则 400。发布时同类型的旧 published 版本自动转 {@code retired}，
   * 不删除——历史订单仍需按当时文本自证。
   *
   * <p>状态语义：已 {@code retired} 的协议永久固化，再发布 409 {@code AGREEMENT_STATE_CONFLICT}；
   * 已是 {@code published} 时直接返回原对象（<b>幂等</b>，重复调用不报错）。
   *
   * @return 200 与发布后的协议
   */
  @PostMapping("/admin/agreements/{agreementId}/publish")
  public ResponseEntity<Object> publish(
      @PathVariable String agreementId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.publish(agreementId, body, context.actor()));
  }
}
