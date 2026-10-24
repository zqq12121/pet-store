package com.warmpaw.gateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/**
 * gateway-server 入口：唯一前端 API 入口，通过 Nacos 发现业务服务；网关不连接业务数据库。
 *
 * <p><b>职责边界</b>：只做路由、鉴权透传与限流，把所有 {@code /api/v1/**} 请求按服务名转发给
 * order-server、admin-server 等下游；自己不实现任何业务规则，也不持有 JDBC 数据源。
 *
 * <p><b>扫描范围</b>：默认扫描本包 {@code com.warmpaw.gateway}，不引入 {@code com.warmpaw}
 * 的公共组件——网关进程与业务进程的依赖集合刻意保持独立，避免业务 Bean 被误装配进来。
 */
@SpringBootApplication
public class GatewayServerApplication {
  public static void main(String[] args) { SpringApplication.run(GatewayServerApplication.class, args); }
}
