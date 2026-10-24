package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AfterSaleApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台售后处理接口（店长视角）：仅适配 HTTP，具体业务在 AfterSaleApplicationService。
 *
 * <p><b>调用方</b>：门店管理端。类上只声明 {@code /api/v1}，各方法自带 {@code /admin} 前缀，
 * 与买家接口共享同一个前缀但不共享权限。
 *
 * <p><b>权限模型</b>：所有方法都显式传 {@link AccessLevel#ADMIN}，最终落到
 * {@code AuthService.role(actor, "admin")}——<b>不是</b> {@code staff}。售后涉及退款、换宠、
 * 检疫凭据，只放给店长；员工即便能进后台也调不通这些端点。
 *
 * <p><b>幂等</b>：读接口 {@code idempotent=false}；所有写接口 {@code idempotent=true}，
 * 由 {@code BusinessOperationExecutor} 要求 UUID 格式的 {@code Idempotency-Key}，
 * 同一键不同请求体返回 409 {@code IDEMPOTENCY_CONFLICT}。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminAfterSaleController {
  private final ApiRequestExecutor requests;
  private final AfterSaleApplicationService service;

  public AdminAfterSaleController(
      ApiRequestExecutor requests, AfterSaleApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /admin/after-sales；访问级别：ADMIN。
   *
   * <p>售后单分页列表，可按 {@code status}、{@code orderNo}、{@code from}/{@code to}、{@code page/pageSize}
   * 过滤。纯读操作，不加业务锁、不要求幂等键，响应 200。
   *
   * <p>{@code query} 原样透传给 service 做字段白名单校验：售后筛选条件不在这里拼，
   * 避免控制器成为第二套参数约定。
   */
  @GetMapping("/admin/after-sales")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.ADMIN, false, context -> service.adminList(query));
  }

  /**
   * GET /admin/after-sales/{saleId}；访问级别：ADMIN。
   *
   * <p>售后单详情（含内联订单、诊断材料、时间线）。虽然调用方是店长，
   * 仍把 {@code actor} 传给 service：归属校验统一走 {@code sales.owned}，
   * 管理员身份在那里放行，非管理员一律 404，控制器不另写一套判断。
   *
   * <p>这是敏感读（含买家个人信息），service 会写 {@code after_sale.read} 审计。
   */
  @GetMapping("/admin/after-sales/{saleId}")
  public ResponseEntity<Object> detail(@PathVariable String saleId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.ADMIN,
        false,
        context -> service.adminDetail(saleId, context.actor()));
  }

  /**
   * PUT /admin/after-sales/{saleId}/exchange；访问级别：ADMIN。
   *
   * <p>店长为换宠选择替换宠物（同价、未退款、可售）。用 PUT 是因为语义是"设置该售后单的替换宠"，
   * 同一售后单重复提交同一个替换宠不应产生新资源，所以成功返回 200 而非 201。
   *
   * <p>请求体可省略：{@code requestBody == null} 时用空 Map 兜底，字段缺失由 service 的白名单校验报
   * 400，而不是在这里判空抛 NPE。
   *
   * <p>写操作，{@code idempotent=true}，必须带 UUID 格式 {@code Idempotency-Key}；
   * 若动作把售后单推进到 {@code refunding}，service 会改为返回 202。
   */
  @PutMapping("/admin/after-sales/{saleId}/exchange")
  public ResponseEntity<Object> exchange(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.exchange(saleId, body, context.actor()));
  }

  /**
   * POST /admin/after-sales/{saleId}/review；访问级别：ADMIN。
   *
   * <p>店长审核售后申请（通过/驳回），请求体带审核结论与理由。用 POST 而非 PATCH：
   * 这是"提交一次审核动作"，会写时间线，不是对资源的字段级修补。
   *
   * <p>状态码：200 表示审核已落库；若通过后直接进入 {@code refunding}，返回 202——退款资金动作是
   * 异步的，不能让人以为钱已到账。
   *
   * <p>写操作，{@code idempotent=true}，需带 {@code Idempotency-Key}。
   */
  @PostMapping("/admin/after-sales/{saleId}/review")
  public ResponseEntity<Object> review(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.review(saleId, body, context.actor()));
  }

  /**
   * POST /admin/after-sales/{saleId}/returns；访问级别：ADMIN。
   *
   * <p>门店验收买家退回的原宠：请求体需带 {@code version}（乐观锁，防止并发操作同一售后单）、
   * {@code received=true}、必填的 {@code conditionNotes} 与可选的 {@code evidenceFileIds} 照片。
   * 验收后原宠一律置 {@code off} 并解除占用——因健康问题退回的宠物不能自动回到可售池，
   * 是否重新上架只能由店长人工判断。
   *
   * <p>成功 200；若验收后转全额退款（{@code refunding}）则返回 202，资金异步处理。
   * 写操作，{@code idempotent=true}，需带 {@code Idempotency-Key}。
   */
  @PostMapping("/admin/after-sales/{saleId}/returns")
  public ResponseEntity<Object> receiveReturn(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.receiveReturn(saleId, body, context.actor()));
  }

  /**
   * POST /admin/after-sales/{saleId}/exchange-delivery；访问级别：ADMIN。
   *
   * <p>把已占用的替换宠正式交付给买家并结案：请求体需带 {@code version}、买家确认单号
   * {@code confirmationId}、{@code quarantineVerified=true} 及（店长可选的）{@code deliveryQuarantine}。
   * 通过后替换宠转 {@code sold}、售后单转 {@code resolved}，属于不可回退的履约动作，故仍限店长执行。
   *
   * <p>成功 200（售后单已存在，不是新建资源，不用 201）。
   * 写操作，{@code idempotent=true}，需带 {@code Idempotency-Key} 防止重复交付。
   */
  @PostMapping("/admin/after-sales/{saleId}/exchange-delivery")
  public ResponseEntity<Object> deliverExchange(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.deliverExchange(saleId, body, context.actor()));
  }

  /**
   * POST /admin/after-sales/{saleId}/exchange-refund；访问级别：ADMIN。
   *
   * <p>换宠协商破裂后的兜底：放弃替换宠、按全额退款结案。请求体需带 {@code version}、{@code reason}
   * 与 {@code buyerConsentFileIds}（1~5 个买家书面同意材料，换宠改退款属于方案变更，必须留证）。
   * 前置条件是原宠已验收退回。
   *
   * <p>状态码：进入 {@code refunding} 时返回 202——退款是异步资金动作，
   * 用"已接受"而不是 200，避免前端提示"已退款"。
   *
   * <p>写操作，{@code idempotent=true}，需带 {@code Idempotency-Key}。
   */
  @PostMapping("/admin/after-sales/{saleId}/exchange-refund")
  public ResponseEntity<Object> refundExchange(
      @PathVariable String saleId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        true,
        context -> service.refundExchange(saleId, body, context.actor()));
  }
}
