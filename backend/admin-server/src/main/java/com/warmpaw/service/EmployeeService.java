package com.warmpaw.service;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单店接待员工管理；角色固定为 staff，不能通过此接口改动店长账号。 */
@Service
@Transactional
public class EmployeeService {
  private final BusinessRepository store;
  private final AuthService auth;

  public EmployeeService(BusinessRepository store, AuthService auth) {
    this.store = store;
    this.auth = auth;
  }

  private void manager(AuthService.Actor actor) {
    AuthService.role(actor, "admin");
    store.lock();
    // 锁内重查，避免重置密码后等待中的旧请求继续修改员工。
    auth.requireActiveSession(actor);
  }

  private Map<String, Object> employee(String id, Input input) {
    Map<String, Object> account = store.get("admin", id);
    require("staff".equals(text(account, "role")), 404, "RESOURCE_NOT_FOUND", "员工不存在");
    BusinessRepository.version(account, input.integer("version", 1, Integer.MAX_VALUE));
    return account;
  }

  /** 始终投影白名单字段，密码摘要不会进入列表或写操作响应。 */
  private Map<String, Object> profile(Map<String, Object> account) {
    Map<String, Object> result = CatalogReader.select(account,
        "id,username,displayName,phone,role,status,version,createdAt,updatedAt");
    String changed = text(account, "passwordChangedAt");
    result.put("passwordChangeAvailableAt", changed == null ? null
        : Instant.parse(changed).plus(Duration.ofDays(7)).toString());
    return result;
  }

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

  private String phone(Input input) {
    String value = input.optional("phone", 11);
    return value == null || value.isBlank() ? null : input.phone("phone");
  }

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

  public Map<String, Object> resetPassword(String id, Map<String, Object> body, AuthService.Actor actor) {
    manager(actor);
    Map<String, Object> account = employee(id, new Input(body, "newPassword,version"));
    auth.resetStaffPassword(account, body);
    store.audit(actor.id(), "employee.password_reset", id);
    return profile(account);
  }
}
