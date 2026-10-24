package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.CatalogApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台宠物管理接口：仅适配 HTTP，具体业务在 CatalogApplicationService（调用方：店长/员工）。
 *
 * <p>路由前缀 {@code /api/v1}，全部挂在 {@code /admin/pets}。
 *
 * <p><b>权限模型刻意分成两档</b>（由 {@link ApiRequestExecutor} 按 {@link AccessLevel} 执行）：
 *
 * <ul>
 *   <li>读接口用 {@code STAFF}（{@code AuthService.staff}）——接待员工需要随时查库存与检疫信息；
 *   <li>写接口用 {@code ADMIN}（{@code AuthService.role(actor, "admin")}）——定价、上下架属于
 *       店长决策，员工令牌一律 403。
 * </ul>
 *
 * <p>所有写操作都在全局写锁内执行，并在锁内二次复核 admin 会话；创建/上架等会改写库存与
 * 买家可见信息，因此这里不做任何客户端可绕过的校验，全部由
 * {@link CatalogApplicationService} → {@code CatalogService} 判定。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminPetController {
  private final ApiRequestExecutor requests;
  private final CatalogApplicationService service;

  public AdminPetController(ApiRequestExecutor requests, CatalogApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /admin/pets；店长和员工均可只读查看（{@code AccessLevel.STAFF}）。
   *
   * <p>与买家列表的关键区别：这里能看到 {@code off}（未上架）与 {@code sold}（已售出）的宠物，
   * 用于盘点与复查；{@code status} 只接受 off/on_sale/reserved/sold，其它值 400。
   *
   * <p>查询参数白名单：{@code keyword/category/status/page/pageSize}，
   * 多传参数会 400（后台不需要买家侧的筛选维度）。只读、幂等。
   *
   * @return 200 与分页结果（后台投影，含版本号与审核备注）
   */
  @GetMapping("/admin/pets")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.STAFF, false, context -> service.adminPets(query));
  }

  /**
   * GET /admin/pets/{petId}；店长和员工均可只读查看（{@code AccessLevel.STAFF}）。
   *
   * <p>返回后台完整投影：包含未上架/已售出状态、{@code version}、检疫材料与
   * {@code lastSaleReviewNote}——员工据此回答"这只为什么下架/什么时候能卖"。
   *
   * <p>相比公开详情，这里可以查到 {@code off} 的宠物（否则上下架操作无从复核）。
   * id 不存在时 404；只读、幂等。
   */
  @GetMapping("/admin/pets/{petId}")
  public ResponseEntity<Object> detail(@PathVariable String petId, HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.STAFF, false, context -> service.adminPet(petId));
  }

  /**
   * POST /admin/pets；访问级别：ADMIN。
   *
   * <p>新建宠物档案，成功 201。<b>新建一律为 {@code off}</b>，必须再走一次上架审核
   * （{@code publish}）才对买家可见——避免"填完就自动开卖"。
   *
   * <p>字段约束（由 CatalogService 校验）：名称 ≤80、品种 ≤50、颜色 ≤30、疫苗/驱虫说明 ≤500、
   * 长文本 ≤10000；价格 1~100000000 分、体重最多两位小数、月龄 0~360、生日不晚于今天；
   * 图片 1~9 张、视频可选，且都必须属于当前操作人、用途匹配（pet_image / pet_video）。
   *
   * <p>非幂等：每次成功生成新 id；创建与图片归属校验在同一事务内。
   *
   * @return 201 与后台宠物投影（{@code status=off}、{@code version=1}）
   */
  @PostMapping("/admin/pets")
  public ResponseEntity<Object> create(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.createPet(body, context.actor()));
  }

  /**
   * PUT /admin/pets/{petId}；访问级别：ADMIN。
   *
   * <p>更新档案，成功 200。必须带 {@code version}（乐观锁），且目标状态只能是
   * {@code off}/{@code on_sale}——{@code sold} 或已被订单占用（{@code reserved}）的宠物不可编辑，
   * 否则会改掉买家已下单商品的历史信息，此类请求 409。
   *
   * <p><b>健康信息变更会自动下架</b>：检疫、疫苗、驱虫、健康描述、名称、品种、性别、生日、
   * 月龄、体重、颜色任一变化（或原检疫证明失效），状态被打回 {@code off} 等待重新审核。
   * 这是"改完不能直接继续卖"的闸门，不是可选项。
   *
   * <p>语义幂等：同一份 body 与 version 重复提交，第二次因版本已变 409，需重新拉取详情。
   *
   * @return 200 与更新后的后台投影（状态可能已变为 off）
   */
  @PutMapping("/admin/pets/{petId}")
  public ResponseEntity<Object> update(
      @PathVariable String petId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.updatePet(petId, body, context.actor()));
  }

  /**
   * POST /admin/pets/{petId}/publish；访问级别：ADMIN。
   *
   * <p>上架，成功 200。body 必须带 {@code version}、{@code reviewNote}(1~1000) 与两个承诺
   * {@code healthyForSale=true}、{@code quarantineVerified=true}。五道检查缺一不可：
   * 检疫证明有效（否则 422 {@code QUARANTINE_REQUIRED}）、未被任何订单占用
   * （409 {@code PET_NOT_AVAILABLE}）、若有退回记录则原售后必须已 resolved/refunded
   * （409 {@code REFUND_IN_PROGRESS}）、承诺勾选、审核备注非空。
   *
   * <p>状态前置条件：只能从 {@code off} 上架，否则 409。成功后写 {@code reviewedAt}/
   * {@code publishedAt} 与 {@code lastSaleReviewNote}，留下"谁基于什么判断可以卖"的记录。
   *
   * <p>幂等性：重复上架会因状态已是 {@code on_sale} 而 409，需先刷新详情确认。
   *
   * @return 200 与上架后的后台投影
   */
  @PostMapping("/admin/pets/{petId}/publish")
  public ResponseEntity<Object> publish(
      @PathVariable String petId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.publishPet(petId, body, context.actor()));
  }

  /**
   * POST /admin/pets/{petId}/unpublish；访问级别：ADMIN。
   *
   * <p>下架，成功 200。body 需要 {@code version} 与 {@code reason}(1~500)。状态前置条件是
   * 只能从 {@code on_sale} 下架——已售出或被订单锁定的宠物无需（也不应）通过下架处理，
   * 否则会让在售订单与库存状态错位。
   *
   * <p>下架不做检疫校验（下架是收紧动作，不涉及对买家的承诺）；原因仅用于审计追溯。
   *
   * <p>幂等性：重复下架 409（状态已不是 on_sale），调用方应按"已下架"处理。
   *
   * @return 200 与下架后的后台投影（{@code status=off}）
   */
  @PostMapping("/admin/pets/{petId}/unpublish")
  public ResponseEntity<Object> unpublish(
      @PathVariable String petId,
      @RequestBody(required = false) Map<String, Object> requestBody,
      HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.ADMIN,
        false,
        context -> service.unpublishPet(petId, body, context.actor()));
  }
}
