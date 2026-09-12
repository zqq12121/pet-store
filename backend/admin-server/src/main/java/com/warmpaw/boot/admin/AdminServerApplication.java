package com.warmpaw.boot.admin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/** admin-server 独立进程入口；启动前加载 backend/.env，默认连接现有 MySQL 与 Redis。 */
@SpringBootApplication(scanBasePackages = "com.warmpaw")
@org.mybatis.spring.annotation.MapperScan("com.warmpaw.mapper")
@EnableScheduling
@org.springframework.cloud.openfeign.EnableFeignClients(basePackages = "com.warmpaw.remote")
public class AdminServerApplication {
  public static void main(String[] args) { SpringApplication.run(AdminServerApplication.class, args); }
  /** 管理侧无交易结果处理，交易操作经 Feign 交给订单服务。 */
  @org.springframework.context.annotation.Bean
  com.warmpaw.application.BusinessHooks adminBusinessHooks() {
    return new com.warmpaw.application.BusinessHooks() {};
  }
}
