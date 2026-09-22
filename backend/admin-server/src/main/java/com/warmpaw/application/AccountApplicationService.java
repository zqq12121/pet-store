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

/** 买家资料用例：身份来自服务端认证结果，不接受客户端指定用户。 */
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

  public OperationResult profile(AuthService.Actor actor) {
    return new OperationResult(200, auth.profile(actor));
  }

  /** 仅允许修改本人资料，头像仍通过文件用途及归属校验。 */
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
