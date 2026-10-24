package com.warmpaw.controller.buyer;

import com.warmpaw.application.AccessLevel;
import com.warmpaw.application.AccountApplicationService;
import com.warmpaw.controller.support.ApiRequestExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 买家资料接口：仅适配 HTTP，具体业务在 AccountApplicationService（调用方：买家）。
 *
 * <p>路由前缀 {@code /api/v1}，路径 {@code /me}。
 *
 * <p><b>权限模型：{@link AccessLevel#BUYER}</b>，即 {@code AuthService.role(actor, "buyer")}。
 * 管理端（店长/员工）令牌访问会 403，游客令牌根本不在识别范围内（未登录 401）。
 * 与 {@code /admin/auth/**} 的区别在于"域"不同：本接口只读写 {@code user} 表。
 *
 * <p>用户 id 永远取自会话，<b>不接受客户端传用户 ID</b>，因此不存在水平越权入口。
 * 写操作在全局写锁内执行，与账号安全写入共用同一把锁。
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {
  private final ApiRequestExecutor requests;
  private final AccountApplicationService service;

  public AccountController(ApiRequestExecutor requests, AccountApplicationService service) {
    this.requests = requests;
    this.service = service;
  }

  /**
   * GET /me；访问级别：BUYER。
   *
   * <p>返回买家自己的资料投影：昵称、头像、用户名、手机号掩码、{@code hasPassword}，
   * 以及两个"下次可修改时间"（密码 7 天、用户名 3 天，用于前端置灰而不是点下去才报 429）。
   *
   * <p>刻意不含 {@code passwordHash} 与原始手机号——只给掩码，避免资料页成为凭据泄露面。
   * 只读、幂等，无查询参数。
   *
   * @return 200 与资料投影
   */
  @GetMapping("/me")
  public ResponseEntity<Object> profile(HttpServletRequest request) {
    return requests.execute(
        request, Map.of(), AccessLevel.BUYER, false, context -> service.profile(context.actor()));
  }

  /**
   * PATCH /me；访问级别：BUYER。
   *
   * <p>局部更新个人资料，只接受 {@code nickname}(1~30) 与 {@code avatarFileId}；
   * 空 body 返回 400（"至少修改一个字段"），出现其它字段同样 400（Input 白名单）。
   *
   * <p>头像不是直接传 URL：服务端会校验该文件属于当前买家、用途为 {@code avatar} 且已就绪，
   * 再取其 {@code publicUrl} 落库，防止把任意外链或他人图片设成头像。
   * {@code avatarFileId=null} 表示清空头像。
   *
   * <p>语义幂等（同一份 body 重复提交结果一致），但每次都会持锁保存并刷新 {@code updatedAt}。
   *
   * @return 200 与更新后的资料投影
   */
  @PatchMapping("/me")
  public ResponseEntity<Object> update(
      @RequestBody(required = false) Map<String, Object> requestBody, HttpServletRequest request) {
    Map<String, Object> body = requestBody == null ? new java.util.LinkedHashMap<>() : requestBody;
    return requests.execute(
        request,
        body,
        AccessLevel.BUYER,
        false,
        context -> service.updateProfile(body, context.actor()));
  }
}
