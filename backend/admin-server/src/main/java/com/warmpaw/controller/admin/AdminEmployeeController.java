package com.warmpaw.controller.admin;

import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.service.AuthService;
import com.warmpaw.service.EmployeeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 店长维护员工账号；所有授权和会话撤销均由事务内的服务执行（调用方：店长）。
 *
 * <p>路由前缀 {@code /api/v1/admin/employees}。
 *
 * <p><b>权限模型</b>：本类不做鉴权，全部交由 {@link EmployeeService}：
 *
 * <ul>
 *   <li>{@code list} 直接 {@code AuthService.role(actor, "admin")}；
 *   <li>其余写方法走私有 {@code manager(actor)}，即在 {@code role(admin)} 之上再加
 *       {@code store.lock()} 与 {@code requireActiveSession}——先锁定再复核会话，
 *       防止刚被重置密码的旧请求继续改员工。
 * </ul>
 *
 * <p>因此<b>员工无法访问本控制器任何接口</b>（403），且这里管理的账号角色固定为 {@code staff}，
 * 不能借它改店长账号。Controller 只负责把 {@code Authorization} 解析成 actor 并透传。
 */
@RestController
@RequestMapping("/api/v1/admin/employees")
public class AdminEmployeeController {
  private final AuthService auth;
  private final EmployeeService employees;

  public AdminEmployeeController(AuthService auth, EmployeeService employees) {
    this.auth = auth;
    this.employees = employees;
  }

  /** 解析 Bearer 令牌；匿名时返回 null，随后由 Service 的 role(admin) 统一转成 401。 */
  private AuthService.Actor actor(HttpServletRequest request) {
    return auth.identify(request.getHeader("Authorization"));
  }

  /**
   * 员工列表。
   *
   * <p>GET {@code /admin/employees}，权限 ADMIN。只查出 {@code role=staff} 的账号，
   * 支持 {@code keyword}（模糊匹配账号/显示名/手机号）、{@code status}(active|disabled)
   * 与 {@code page/pageSize}。
   *
   * <p>只读事务、不加锁、幂等。返回字段走白名单投影，密码摘要不会出现，
   * 但会附 {@code passwordChangeAvailableAt} 供前端置灰重置按钮。
   *
   * @return 200 与分页结果
   */
  @GetMapping
  public ResponseEntity<Object> list(@RequestParam Map<String, Object> query, HttpServletRequest request) {
    return ok(200, employees.list(query, actor(request)));
  }

  /**
   * 新建员工账号。
   *
   * <p>POST {@code /admin/employees}，权限 ADMIN，成功 201。字段
   * {@code username/displayName/phone/password}：账号须字母开头、3~32 位
   * （字母/数字/下划线），重复返回 409 {@code USERNAME_TAKEN}；初始密码 12~128 位且
   * 必须同时含字母与数字，否则 400。
   *
   * <p>角色由服务端固定写为 {@code staff}、状态 {@code active}，客户端无法指定——
   * 这是"不能通过员工接口造出一个店长"的关键。
   *
   * <p>非幂等：重复提交会因账号已存在而 409（不会创建第二个账号）。
   *
   * @return 201 与员工资料投影
   */
  @PostMapping
  public ResponseEntity<Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(201, employees.create(body, actor(request)));
  }

  /**
   * 修改员工资料（仅显示名与手机号）。
   *
   * <p>PUT {@code /admin/employees/{id}}，权限 ADMIN。可改字段
   * {@code displayName}(1~30)、{@code phone}(可空，空串落库为 null)，外加必填的 {@code version}。
   * 目标不是 staff 角色时 404（不暴露"这是店长账号"）。
   *
   * <p>有副作用但语义幂等：同一份 body 与 version 重复提交，第二次会因版本已变而 409，
   * 客户端应重新拉取再改。
   *
   * @return 200 与更新后的员工资料
   */
  @PutMapping("/{id}")
  public ResponseEntity<Object> update(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(200, employees.update(id, body, actor(request)));
  }

  /**
   * 启用 / 停用员工账号。
   *
   * <p>PUT {@code /admin/employees/{id}/status}，权限 ADMIN。body 为
   * {@code status=active|disabled} 与 {@code version}。
   *
   * <p>无论启用还是停用都会撤销该员工已签发的 token（{@code auth.revokeAll}）：停用要立刻踢下线，
   * 重新启用也不恢复停用前的旧凭证——否则"先停用再启用"就成了绕过强制重新登录的手段。
   * 真正的下线还依赖 {@code identify} 的实时权限复核（disabled 即 401）。
   *
   * <p>幂等：重复设置同一状态结果一致（version 需随每次响应递增）。
   *
   * @return 200 与最新员工资料
   */
  @PutMapping("/{id}/status")
  public ResponseEntity<Object> status(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(200, employees.status(id, body, actor(request)));
  }

  /**
   * 店长重置员工密码。
   *
   * <p>PUT {@code /admin/employees/{id}/password}，权限 ADMIN。body 为
   * {@code newPassword}(12~128，字母+数字) 与 {@code version}。
   *
   * <p>复用 {@code AuthService.resetStaffPassword}，因此同样受 7 天冷却约束（429
   * {@code CHANGE_COOLDOWN}），并撤销该员工全部会话——员工必须用新密码重新登录。
   * 服务端不返回明文或哈希。
   *
   * <p>非幂等：重置动作本身有审计与时间戳，重复调用受冷却与版本号双重限制。
   *
   * @return 200 与员工资料（含新的可改密时间）
   */
  @PutMapping("/{id}/password")
  public ResponseEntity<Object> password(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(200, employees.resetPassword(id, body, actor(request)));
  }
}
