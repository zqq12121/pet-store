package com.warmpaw.boot.order;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/**
 * order-server 独立进程入口：订单、预约、售后、支付回调与退款工作器都跑在这里。
 *
 * <p>启动前加载 backend/.env，默认连接现有 MySQL 与 Redis。
 *
 * <p><b>扫描范围</b>：{@code scanBasePackages = "com.warmpaw"}，比本包更宽——
 * {@code AuthService}、{@code ApiRequestExecutor}、{@code BusinessRepository} 等公共组件在
 * common 模块里，必须一并扫描才能注入；{@code @MapperScan("com.warmpaw.mapper")} 注册 MyBatis 映射器。
 *
 * <p><b>在架构中的角色</b>：交易域的唯一后端。gateway-server 只做路由转发，不连业务库。
 * {@code @EnableScheduling} 是为预约超时、退款重试、短信补发这些定时工作器准备的。
 */
@SpringBootApplication(scanBasePackages = "com.warmpaw")
@org.mybatis.spring.annotation.MapperScan("com.warmpaw.mapper")
@EnableScheduling
public class OrderServerApplication {
  public static void main(String[] args) { SpringApplication.run(OrderServerApplication.class, args); }
  /** 订单事务只读取目录快照，目录写入实现不在本服务的依赖中。 */
  @org.springframework.context.annotation.Bean
  com.warmpaw.service.CatalogReader catalogReader(com.warmpaw.repository.BusinessRepository store,
      com.warmpaw.repository.PetQueries queries) {
    return new com.warmpaw.service.CatalogReader(store, queries);
  }
}
