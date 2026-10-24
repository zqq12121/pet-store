package com.warmpaw.controller.admin;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.OrderApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 后台订单查询接口：仅适配 HTTP，具体业务在 OrderApplicationService。
 *
 * <p><b>调用方</b>：门店后台（店长与员工共用）。路由前缀 {@code /api/v1}，方法内各自声明
 * {@code /admin/orders...}。
 *
 * <p><b>权限模型</b>：读接口是 {@link AccessLevel#STAFF}（{@code AuthService.staff}，
 * 店长与员工都放行）；唯一的写接口是预约动作，权限按 {@code action} 动态决定——
 * {@code complete} 走 STAFF，{@code confirm}/{@code cancel} 走 {@link AccessLevel#ADMIN}。
 * 所以这个类里同时存在两种权限路径，不能按 {@code /admin} 前缀统一推断。
 *
 * <p><b>业务委托</b>：{@link OrderApplicationService}；状态机与权限的二次校验在
 * {@code AppointmentService.process} 内完成，控制器这层的判断只是第一道门。
 */
@RestController
@RequestMapping("/api/v1")
public class AdminOrderController {
  private final ApiRequestExecutor requests;
  private final OrderApplicationService service;

  public AdminOrderController(ApiRequestExecutor requests, OrderApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * POST /admin/orders/{orderId}/appointment/{action}：预约确认、取消、线下收款交付；
   * 员工仅允许登记完成，确认和取消由店长操作。
   *
   * <p><b>权限</b>在这里按 {@code action} 三目选择：{@code complete} → STAFF
   * （{@code AuthService.staff}），其余 → ADMIN（{@code role(actor,"admin")}）。
   * 与 {@code AppointmentService.process} 内的判断重复是有意的：一处拦在入口，
   * 一处拦在领域层，任何一方被绕过都还有另一道。
   *
   * <p>{@code action} 的合法值（{@code confirm}/{@code cancel}/{@code complete}）由 service 校验，
   * 未知值返回 404 而不是 400——对外表现为"这个操作不存在"。
   *
   * <p>写操作，{@code idempotent=true}，需带 UUID 格式 {@code Idempotency-Key}：
   * 交付会改库存与收款状态，重放必须复用首次结果而不是再执行一次。成功返回 200，
   * 因为改的是既有订单而不是新建资源。
   */
  @PostMapping("/admin/orders/{orderId}/appointment/{action}")
  public ResponseEntity<Object> appointment(@PathVariable String orderId, @PathVariable String action,
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
    return requests.execute(request, body, "complete".equals(action) ? AccessLevel.STAFF : AccessLevel.ADMIN, true,
        context -> service.appointmentAction(orderId, action, body, context.actor()));
  }

  /**
   * GET /admin/orders；访问级别：STAFF。
   *
   * <p>后台订单列表（含联系人、备注），支持 {@code orderNo/status/contactPhone/petId/
   * createdFrom/createdTo/visitDate/page/pageSize}。与买家列表是两套字段白名单：
   * 员工可用的筛选维度更多，这些校验在 service 里做。
   *
   * <p>按 {@code contactPhone} 搜索会写 {@code order.phone_search} 审计——手机号是最敏感的
   * 查询维度，必须留痕。只读，不要求幂等键，固定 200。
   */
  @GetMapping("/admin/orders")
  public ResponseEntity<Object> list(
      @RequestParam Map<String, Object> query, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.STAFF,
        false,
        context -> service.adminList(query, context.actor()));
  }

  /**
   * GET /admin/orders/{orderId}；访问级别：STAFF。
   *
   * <p>后台订单详情：不限归属（员工要服务任意到店客人），但视图是 {@code admin=true}，
   * 会额外下发 {@code userId} 与完整手机号，因此 service 里写 {@code order.read} 审计。
   *
   * <p>订单不存在时由 {@code store.get} 抛 404；控制器不做额外判空。
   */
  @GetMapping("/admin/orders/{orderId}")
  public ResponseEntity<Object> detail(@PathVariable String orderId, HttpServletRequest request) {
    return requests.execute(
        request,
        Map.of(),
        AccessLevel.STAFF,
        false,
        context -> service.adminDetail(orderId, context.actor()));
  }
}
