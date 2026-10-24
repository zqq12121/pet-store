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

/**
 * 首次启动初始化单店与管理员。模拟数据仅存在 local/test profile，生产启动强制关闭模拟服务。
 *
 * <p><b>只在"缺"的时候写</b>（{@code byKey} 查不到才 {@code create}），所以它是幂等的：
 * 多次启动、两个实例同时启动都不会产生第二份数据。也因此它是<b>一次性的</b>——
 * 之后改配置（{@code PAW_ADMIN_PASSWORD}、门店默认值）<b>不会</b>影响已经存在的行，
 * 改密码必须走后台改密接口。这一点经常被误解为"改环境变量就能重置密码"。
 *
 * <p><b>三道启动闸门</b>，任何一条不过都直接让进程起不来（而不是带病运行）：
 *
 * <ol>
 *   <li><b>旧库检测</b>——存在旧 {@code resources} 表且有数据、但迁移记录里没有
 *       {@code relational_v2} 时拒绝启动。否则会在一张"看起来空"的新表上初始化一套数据，
 *       同时把本地管理员密码覆盖掉，属于不可逆的数据事故；
 *   <li><b>模拟服务只能在 local/test</b>——{@code app.mock-providers=true} 但 profile 不是
 *       local/test 时拒绝启动。模拟服务会让短信不真发、支付不真收，混进生产就是资损；
 *   <li><b>管理员密码强度</b>——少于 12 位拒绝启动。首次启动若未提供密码，
 *       本地模式随机生成并写到 {@code data/local-admin-password.txt}（0600），
 *       非本地模式直接报错要求显式配置。
 * </ol>
 *
 * <p><b>为什么在 {@code run} 上开事务</b>：管理员与门店的插入跨多张表（含子表），
 * 并持有全局写锁，必须原子完成；中途失败要整体回滚，不能留下"有管理员没门店"的半初始化状态。
 */
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

  /**
   * 启动时执行一次初始化（{@link ApplicationRunner} 在容器就绪后、对外服务前调用）。
   *
   * <p>流程：旧库检测 → profile 与模拟模式一致性 → 取全局写锁 → 缺则建管理员 → 缺则建门店。
   *
   * <p><b>先取锁再查再建</b>：{@code store.lock()} 是为了让多实例同时启动时只有一个能完成初始化，
   * 另一个会阻塞到锁释放后发现数据已存在，从而跳过。
   *
   * <p>管理员用 {@code username} 作为业务键（{@code byKey("admin", username)}），
   * 与后台登录的查询方式一致；门店固定 id {@code shop_1}、业务键 {@code single}——
   * 单店部署，业务键只是个占位的常量。
   *
   * <p><b>门店默认值刻意"不可用"</b>：{@code businessHours} 填 {@code "请联系门店确认"}，
   * 这句话不满足 {@code AppointmentHours} 的格式，因此预约接口会以 422 拒绝，
   * 直到店长在后台填好真实营业时间。这是失败关闭——默认值宁可让功能不可用，
   * 也不能让顾客预约到闭店时段。
   *
   * <p>初始化完成后不会再有"补齐默认值"的逻辑：之后新增的默认字段不会回填到老门店。
   */
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
    // mock 开关与 profile 必须一致：否则可能出现"生产环境发不出真短信"的静默故障。
    if (auth.local() && !local) throw new IllegalStateException("模拟服务只能在 local/test 环境启用");
    // 先拿全局写锁，避免多实例同时启动各建一份管理员/门店。
    store.lock();
    if (store.byKey("admin", username) == null) {
      String secret = password;
      if (secret.isBlank()) {
        if (!local) throw new IllegalStateException("首次启动必须设置 PAW_ADMIN_PASSWORD");
        // 本地模式随机生成，落盘为 0600，权限只给当前用户。
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
    // id 固定为 shop_1，与 CatalogReader.shop() 的取值一致；业务键 "single" 只是占位。
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
