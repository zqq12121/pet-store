package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;

import com.warmpaw.service.AuthService;
import java.util.List;

/**
 * 每个接口显式声明访问级别，避免用 URL 前缀推断权限。
 *
 * <p>为什么不用"路径前缀 + 拦截器"：{@code /api/v1/admin/**} 这种约定一旦有人加了
 * 一个不该放在 admin 路径下的接口，权限就默认为"管理员可用"——默认值错在<b>过宽</b>。
 * 这里要求每个 controller 方法把级别作为字面量传进执行器，漏写就编译不过，
 * 且评审时一眼能看出这个接口属于哪一级。
 *
 * <p>级别之间的包含关系<b>不是</b>线性的：{@code STAFF} 包含 {@code admin}（店长也是员工），
 * {@code VISITOR} 包含 {@code buyer} 与 {@code guest} 但不是"更高级"（它恰恰是更低门槛）。
 * 所以这里是"面向谁"的分类，而不是"权限高低"的序数，不要拿 {@code ordinal()} 做比较。
 */
public enum AccessLevel {
  /** 完全公开：不读身份，未登录也能访问（首页、商品列表、门店信息）。 */
  PUBLIC,
  /** 仅已登录买家（{@code role=buyer}）；管理端账号走这个入口会被 403。 */
  BUYER,
  /** 仅店长（{@code role=admin}）：定价、上下架、退款审批、员工管理。 */
  ADMIN,
  /** 店内工作人员（店长或员工）：核销、查单等日常操作。 */
  STAFF,
  /** 买家或游客二选一：需要身份归属但不强制登录（浏览统计、AI 会话）。 */
  VISITOR;

  /**
   * 断言主体满足本级别，否则抛 401/403。
   *
   * <p>错误码区分很重要：未登录 401（前端该去登录），登录了但角色不对 403
   * （前端该提示无权限，而不是把用户踢去登录页）。
   *
   * <p>{@code VISITOR} 的 401 是"缺少访问身份"——买家身份与游客身份都没有的情况；
   * 若请求同时带了登录 token 与游客 token，会在 HTTP 适配层被判 400（身份只能选一种），
   * 不是在这里处理。
   */
  public void check(AuthService.Actor actor) {
    switch (this) {
      case PUBLIC -> {}
      case BUYER -> AuthService.role(actor, "buyer");
      case ADMIN -> AuthService.role(actor, "admin");
      case STAFF -> AuthService.staff(actor);
      case VISITOR ->
          require(
              actor != null && List.of("buyer", "guest").contains(actor.role()),
              401,
              "UNAUTHORIZED",
              "缺少访问身份");
    }
  }
}
