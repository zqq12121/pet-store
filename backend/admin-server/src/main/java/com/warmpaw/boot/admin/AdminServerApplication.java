package com.warmpaw.boot.admin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/**
 * admin-server 独立进程入口；启动前加载 backend/.env，默认连接现有 MySQL 与 Redis。
 *
 * <p><b>在微服务中的角色</b>：承担认证、宠物目录、门店信息、社区、文件与后台管理接口，
 * 同时为买家端提供大部分只读查询；<b>不处理交易写操作</b>——订单、预约、支付、售后
 * 由 order-server 负责，两者经 Nacos 发现并通过 OpenFeign 通信
 * （出站客户端见 {@code com.warmpaw.remote.OrderAdminClient}）。
 *
 * <p><b>扫描范围</b>：
 *
 * <ul>
 *   <li>{@code scanBasePackages = "com.warmpaw"} —— 复用 common 模块里的业务服务与安全组件，
 *       不为本服务单独复制一份；
 *   <li>{@code @MapperScan("com.warmpaw.mapper")} —— 持久层 Mapper 接口；
 *   <li>{@code @EnableFeignClients(basePackages = "com.warmpaw.remote")} —— 只扫描远程客户端包，
 *       避免把普通接口误注册成 Feign 代理。
 * </ul>
 *
 * <p>{@code @EnableScheduling} 供每日维护任务使用（清理未被业务引用的临时文件），
 * 与订单侧的预约超时/短信重试任务分属不同进程，互不阻塞。
 *
 * <p>MySQL/Redis 是硬依赖：未就绪时启动即失败，不做"无库降级"运行。
 */
@SpringBootApplication(scanBasePackages = "com.warmpaw")
@org.mybatis.spring.annotation.MapperScan("com.warmpaw.mapper")
@EnableScheduling
@org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.warmpaw.remote")
public class AdminServerApplication {
  /** 标准 Spring Boot 启动入口；参数与配置由外部环境变量/配置文件提供，代码里不写死端口。 */
  public static void main(String[] args) { SpringApplication.run(AdminServerApplication.class, args); }
  /**
   * 管理侧无交易结果处理，交易操作经 Feign 交给订单服务。
   *
   * <p>{@code BusinessHooks} 是公共执行器在事务提交后的扩展点（订单服务用它发通知、写 outbox）。
   * admin-server 不产生交易终态，因此提供一个空实现来满足依赖注入——<b>不是忘了写</b>：
   * 若把订单侧实现放进本服务，会因为缺少订单库上下文而失败。
   */
  @org.springframework.context.annotation.Bean
  com.warmpaw.application.BusinessHooks adminBusinessHooks() {
    return new com.warmpaw.application.BusinessHooks() {};
  }
}
