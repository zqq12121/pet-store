package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单店接待员工管理；角色固定为 staff，不能通过此接口改动店长账号。
 *
 * <p><b>边界</b>：只管 {@code admin} 表里 {@code role=staff} 的行。店长账号在本类里一律表现为
 * "员工不存在"（404，见 {@link #employee}），因此<b>本类根本不提供删除操作</b>——
 * 离职只能停用（{@link #status}），"不能删除自己"这件事是靠"没有删除能力 + 改不到 admin 行"
 * 两层共同保证的，不是靠一条 if 判断。
 *
 * <p><b>为什么整类 {@code @Transactional}</b>：每个写方法都是"加锁 → 重查版本 → 写 → 审计"，
 * 必须在同一事务里；只有 {@link #list} 显式声明 {@code readOnly}。
 *
 * <p><b>会话处理</b>：{@link #status} 无论启用还是停用都会撤销该员工全部会话。停用后旧 token
 * 必须立刻失效（{@code AuthService.identify} 每次请求还会复核账号状态，这里是双保险）；
 * 重新启用也不恢复停用前的凭证，必须重新登录。
 *
 * <p><b>密码策略不在这里</b>：重置密码全部委托 {@code AuthService.resetStaffPassword}，
 * 因此同样受"7 天冷却 + 改密即撤销全部会话"的约束，本类只负责权限与版本校验。
 */
@Service
@Transactional
public class EmployeeService {
  private final BusinessRepository store;
  private final AuthService auth;

  public EmployeeService(BusinessRepository store, AuthService auth) {
    this.store = store;
    this.auth = auth;
  }

  /**
   * 管理动作的前置：必须店长、先拿全局写锁、再在锁内复核会话。
   *
   * <p>为什么锁内还要一次 {@code requireActiveSession}：请求进入时校验过一次，但可能排队等锁很久，
   * 这期间店长自己可能刚改过密码（改密会撤销该账号全部会话）。不复查就会出现
   * "已经被登出的请求仍然改了员工密码"。
   */
  private void manager(AuthService.Actor actor) {
    AuthService.role(actor, "admin");
    store.lock();
    // 锁内重查，避免重置密码后等待中的旧请求继续修改员工。
    auth.requireActiveSession(actor);
  }

  /**
   * 取员工并校验版本号；不是 {@code staff} 一律 404。
   *
   * <p>用 404 而不是 403：把"这是个店长账号"本身也藏起来，同时天然阻止通过员工接口改动
   * 店长的显示名、手机号或状态。版本号在这里被消费一次，之后的 {@code save} 会自增。
   */
  private Map<String, Object> employee(String id, Input input) {
    Map<String, Object> account = store.get("admin", id);
    require("staff".equals(text(account, "role")), 404, "RESOURCE_NOT_FOUND", "员工不存在");
    BusinessRepository.version(account, input.integer("version", 1, Integer.MAX_VALUE));
    return account;
  }

  /**
   * 始终投影白名单字段，密码摘要不会进入列表或写操作响应。
   *
   * <p>{@code passwordChangeAvailableAt} 用 {@code passwordChangedAt + 7 天} 现算，
   * 与 {@code AuthService} 的冷却口径一致；从没改过密码时为 null，表示"现在就可以设置"。
   */
  private Map<String, Object> profile(Map<String, Object> account) {
    Map<String, Object> result = CatalogReader.select(account,
        "id,username,displayName,phone,role,status,version,createdAt,updatedAt");
    String changed = text(account, "passwordChangedAt");
    result.put("passwordChangeAvailableAt", changed == null ? null
        : Instant.parse(changed).plus(Duration.ofDays(7)).toString());
    return result;
  }

  /**
   * 员工列表：只列 {@code staff}，支持状态与关键词过滤，分页交给 {@code CatalogReader.page}。
   *
   * <p>关键词在 username / displayName / phone 三个字段上做大小写不敏感的子串匹配；
   * 手机号在这里是明文可搜的（店长本来就靠它认人），掩码只出现在对外的买家资料里。
   *
   * <p>{@code status} 不传表示不过滤，传了就必须是 {@code active}/{@code disabled}，否则 400。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> list(Map<String, Object> query, AuthService.Actor actor) {
    AuthService.role(actor, "admin");
    Input input = new Input(query, "keyword,status,page,pageSize");
    String keyword = Objects.toString(input.optional("keyword", 50), "").toLowerCase(Locale.ROOT);
    String status = input.has("status") ? input.choice("status", "active,disabled") : null;
    return CatalogReader.page(store.list("admin").stream()
        .filter(a -> "staff".equals(text(a, "role")))
        .filter(a -> status == null || status.equals(text(a, "status")))
        .filter(a -> List.of("username", "displayName", "phone").stream()
            .anyMatch(k -> Objects.toString(a.get(k), "").toLowerCase(Locale.ROOT).contains(keyword)))
        .map(this::profile).toList(), query);
  }

  /**
   * 新建员工账号。
   *
   * <p>角色在代码里写死 {@code staff}：请求里即使带 {@code role} 也会被 {@code Input} 的字段
   * 白名单拒掉（400），所以不存在"创建时顺手提权成店长"的路径。
   *
   * <p>用户名规则与买家不同：这里<b>大小写敏感</b>（正则允许大写，也不做 {@code toLowerCase}），
   * 与后台登录按原样查 {@code admin} 表的行为保持一致；唯一性用 {@code byKey} 判定，冲突 409。
   *
   * <p>密码要求 12~128 位且同时含字母和数字，落库前由 {@code AuthService.passwordHash}
   * 做 PBKDF2 加盐，本类从头到尾不接触明文。
   */
  public Map<String, Object> create(Map<String, Object> body, AuthService.Actor actor) {
    manager(actor);
    Input input = new Input(body, "username,displayName,phone,password");
    String username = input.str("username", 3, 32);
    require(username.matches("[a-zA-Z][a-zA-Z0-9_]{2,31}"), 400, "VALIDATION_ERROR",
        "账号须以字母开头，包含3至32位字母、数字或下划线");
    require(store.byKey("admin", username) == null, 409, "USERNAME_TAKEN", "账号已被使用");
    String password = input.str("password", 12, 128);
    require(password.matches(".*[a-zA-Z].*") && password.matches(".*[0-9].*"),
        400, "VALIDATION_ERROR", "密码须为12至128位，包含字母和数字");
    Map<String, Object> account = store.create("admin", null, username,
        map("username", username, "displayName", input.str("displayName", 1, 30),
            "phone", phone(input), "role", "staff", "status", "active",
            "passwordHash", auth.passwordHash(password)));
    store.audit(actor.id(), "employee.create", text(account, "id"));
    return profile(account);
  }

  /**
   * 可选手机号：未填或全空白存 {@code null} 而不是空串，避免"有手机号但打不通"这种歧义状态；
   * 填了就走 {@code input.phone} 的格式校验（11 位），不会把半截号码写进去。
   */
  private String phone(Input input) {
    String value = input.optional("phone", 11);
    return value == null || value.isBlank() ? null : input.phone("phone");
  }

  /**
   * 修改员工资料：只有 displayName 与 phone。
   *
   * <p>用户名、角色、状态都不在白名单里——改名会让员工的登录习惯断裂，改角色等于提权，
   * 改状态必须走 {@link #status}（那里会撤销会话）。所以这个方法不会产生"静默提权"，
   * 也不会绕过停用流程把账号悄悄恢复。
   */
  public Map<String, Object> update(String id, Map<String, Object> body, AuthService.Actor actor) {
    manager(actor);
    Input input = new Input(body, "displayName,phone,version");
    Map<String, Object> account = employee(id, input);
    account.put("displayName", input.str("displayName", 1, 30));
    account.put("phone", phone(input));
    store.save(account);
    store.audit(actor.id(), "employee.update", id);
    return profile(account);
  }

  /**
   * 启用 / 停用员工。
   *
   * <p>{@code revokeSessions} 在两种切换方向上都执行，不是只在停用时：如果重新启用后旧 token 还有效，
   * "停用 → 启用"就成了延长会话寿命的漏洞（被停用者只要掐着时间点恢复即可）。
   *
   * <p>审计动作按结果拼接（{@code employee.active} / {@code employee.disabled}），
   * 从审计日志就能看出这次是停用还是恢复。
   */
  public Map<String, Object> status(String id, Map<String, Object> body, AuthService.Actor actor) {
    manager(actor);
    Input input = new Input(body, "status,version");
    Map<String, Object> account = employee(id, input);
    account.put("status", input.choice("status", "active,disabled"));
    store.save(account);
    // 停用必须撤销旧会话，重新启用也不能恢复停用前的凭证。
    store.revokeSessions(id, "staff");
    store.audit(actor.id(), "employee." + text(account, "status"), id);
    return profile(account);
  }

  /**
   * 店长重置员工密码。
   *
   * <p>版本号在这里被消费（{@code new Input("newPassword,version")} 交给 {@link #employee}），
   * 真正的写入与"撤销该员工全部会话"在 {@code AuthService.resetStaffPassword} 内完成，
   * 并同样受 7 天冷却约束。
   *
   * <p>注意 {@code body} 被用了两次（一次取版本、一次交给密码服务）：字段白名单两处都写死
   * {@code newPassword,version}，所以这里不会漏掉"多余字段"的校验。
   */
  public Map<String, Object> resetPassword(String id, Map<String, Object> body, AuthService.Actor actor) {
    manager(actor);
    Map<String, Object> account = employee(id, new Input(body, "newPassword,version"));
    auth.resetStaffPassword(account, body);
    store.audit(actor.id(), "employee.password_reset", id);
    return profile(account);
  }
}
