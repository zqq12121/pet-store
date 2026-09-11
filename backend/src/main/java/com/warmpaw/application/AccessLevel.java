package com.warmpaw.application;

import static com.warmpaw.common.ApiException.require;

import com.warmpaw.service.AuthService;
import java.util.List;

/** 每个接口显式声明访问级别，避免用 URL 前缀推断权限。 */
public enum AccessLevel {
  PUBLIC,
  BUYER,
  ADMIN,
  VISITOR;

  public void check(AuthService.Actor actor) {
    switch (this) {
      case PUBLIC -> {}
      case BUYER -> AuthService.role(actor, "buyer");
      case ADMIN -> AuthService.role(actor, "admin");
      case VISITOR ->
          require(
              actor != null && List.of("buyer", "guest").contains(actor.role()),
              401,
              "UNAUTHORIZED",
              "缺少访问身份");
    }
  }
}
