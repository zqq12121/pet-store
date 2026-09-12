package com.warmpaw.config;

import static com.warmpaw.common.Json.*;

import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 首次启动初始化单店与管理员。模拟数据仅存在 local/test profile，生产启动强制关闭模拟服务。 */
@Component
public class Bootstrap implements ApplicationRunner {
  private final BusinessRepository store;
  private final AuthService auth;
  private final Environment env;
  private final String username, password;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  public Bootstrap(
      BusinessRepository store,
      AuthService auth,
      Environment env,
      org.springframework.jdbc.core.JdbcTemplate jdbc,
      @Value("${app.admin-username}") String username,
      @Value("${app.admin-password}") String password) {
    this.store = store;
    this.auth = auth;
    this.env = env;
    this.jdbc = jdbc;
    this.username = username;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    // 防止旧H2/MySQL文件未迁移时，静默初始化一套空表并覆盖本地管理员密码。
    boolean legacy =
        Boolean.TRUE.equals(
            jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                    connection -> {
                      try (var tables =
                          connection
                              .getMetaData()
                              .getTables(
                                  connection.getCatalog(),
                                  connection.getSchema(),
                                  "resources",
                                  null)) {
                        return tables.next();
                      }
                    }));
    if (legacy
        && jdbc.queryForObject("SELECT COUNT(*) FROM resources", Integer.class) > 0
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version='relational_v2'",
                Integer.class)
            == 0) throw new IllegalStateException("检测到未迁移的旧 resources 数据，请先备份并执行关系表迁移；不会初始化空业务库");
    boolean local = env.matchesProfiles("local", "test");
    if (auth.local() && !local) throw new IllegalStateException("模拟服务只能在 local/test 环境启用");
    store.lock();
    if (store.byKey("admin", username) == null) {
      String secret = password;
      if (secret.isBlank()) {
        if (!local) throw new IllegalStateException("首次启动必须设置 PAW_ADMIN_PASSWORD");
        secret = auth.randomToken();
        Path p = Path.of("data/local-admin-password.txt");
        Files.createDirectories(p.getParent());
        Files.writeString(p, secret);
        Files.setPosixFilePermissions(
            p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      }
      if (secret.length() < 12) throw new IllegalStateException("管理员初始密码至少12位");
      store.create(
          "admin",
          null,
          username,
          map("username", username, "passwordHash", auth.passwordHash(secret)));
    }
    if (store.find("shop", "shop_1") == null)
      store.create(
          "shop",
          null,
          "single",
          map(
              "id",
              "shop_1",
              "name",
              "暖爪宠物生活馆",
              "address",
              local ? "本地开发演示地址" : "待店主配置",
              "latitude",
              30.28,
              "longitude",
              120.12,
              "coordinateSystem",
              "gcj02",
              "phone",
              "待配置",
              "wechat",
              "待配置",
              "businessHours",
              "请联系门店确认",
              "pickupInstructions",
              "请携带本人手机与航空箱到店核验。",
              "banners",
              List.of(),
              "paymentTimeoutMinutes",
              30,
              "pickupRetentionHours",
              72,
              "exchangeEnabled",
              false));
  }
}
