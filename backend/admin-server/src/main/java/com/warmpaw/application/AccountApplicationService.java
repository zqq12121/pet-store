package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;
import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 买家资料用例：查看与修改自己的昵称、头像。
 *
 * <p><b>身份只来自服务端认证结果</b>：所有方法都接收 {@link AuthService.Actor}，
 * 从不接受客户端传入 userId。这样即使请求体里塞了 {@code userId} 也会被
 * {@link Input} 的字段白名单挡掉（400 存在不允许提交的字段），不存在越权改他人资料的可能。
 *
 * <p>返回视图统一走 {@code auth.profile(actor)}，与登录响应里的 user 结构一致，
 * 前端不需要为"登录后"和"刷新资料后"写两套映射。
 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class AccountApplicationService {
  private final BusinessRepository store;
  private final CatalogService catalog;
  private final AuthService auth;

  public AccountApplicationService(
      BusinessRepository store, CatalogService catalog, AuthService auth) {
    this.store = store;
    this.catalog = catalog;
    this.auth = auth;
  }

  /** 当前登录买家的资料。手机号只给掩码，密码只给"是否已设置"的布尔值。 */
  public OperationResult profile(AuthService.Actor actor) {
    return new OperationResult(200, auth.profile(actor));
  }

  /**
   * 修改本人资料（昵称 / 头像）。
   *
   * <p>三条约束：
   *
   * <ul>
   *   <li>空对象直接 400——避免"什么也没改"的无效写产生一条审计噪音；
   *   <li>头像必须过 {@code asset(fileId, "avatar", actor.id(), null)} 校验：
   *       文件用途必须是 avatar、且归属必须是当前用户，防止拿别人的图当头像；
   *   <li>{@code avatarFileId} 显式传 {@code null} 表示清空头像，与"不传"（保持不变）区分。
   * </ul>
   *
   * <p>加锁原因：资料写与账号安全写（改密、改用户名）操作同一行 {@code user} 记录。
   * 不加锁时，一次资料保存可能用旧对象覆盖掉刚写入的 {@code passwordHash} 或冷却时间戳。
   *
   * <p>头像仍通过文件用途及归属校验，只有本人上传的 avatar 文件能被设为头像。
   */
  public OperationResult updateProfile(Map<String, Object> b, AuthService.Actor a) {
    Input in = new Input(b, "nickname,avatarFileId");
    require(!b.isEmpty(), 400, "VALIDATION_ERROR", "至少修改一个字段");
    // 与账号安全写入使用相同锁，避免资料保存覆盖新密码或冷却时间。
    store.lock();
    Map<String, Object> u = store.get("user", a.id());
    if (in.has("nickname")) u.put("nickname", in.str("nickname", 1, 30));
    if (in.has("avatarFileId"))
      u.put(
          "avatarUrl",
          b.get("avatarFileId") == null
              ? null
              : catalog
                  .asset(in.str("avatarFileId", 1, 64), "avatar", a.id(), null)
                  .get("publicUrl"));
    store.save(u);
    return new OperationResult(200, auth.profile(a));
  }
}
