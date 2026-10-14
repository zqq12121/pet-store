package com.warmpaw.controller.admin;

import static com.warmpaw.controller.support.ApiResponses.ok;

import com.warmpaw.service.AuthService;
import com.warmpaw.service.EmployeeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 店长维护员工账号；所有授权和会话撤销均由事务内的服务执行。 */
@RestController
@RequestMapping("/api/v1/admin/employees")
public class AdminEmployeeController {
  private final AuthService auth;
  private final EmployeeService employees;

  public AdminEmployeeController(AuthService auth, EmployeeService employees) {
    this.auth = auth;
    this.employees = employees;
  }

  private AuthService.Actor actor(HttpServletRequest request) {
    return auth.identify(request.getHeader("Authorization"));
  }

  @GetMapping
  public ResponseEntity<Object> list(@RequestParam Map<String, Object> query, HttpServletRequest request) {
    return ok(200, employees.list(query, actor(request)));
  }

  @PostMapping
  public ResponseEntity<Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(201, employees.create(body, actor(request)));
  }

  @PutMapping("/{id}")
  public ResponseEntity<Object> update(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(200, employees.update(id, body, actor(request)));
  }

  @PutMapping("/{id}/status")
  public ResponseEntity<Object> status(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(200, employees.status(id, body, actor(request)));
  }

  @PutMapping("/{id}/password")
  public ResponseEntity<Object> password(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    return ok(200, employees.resetPassword(id, body, actor(request)));
  }
}
