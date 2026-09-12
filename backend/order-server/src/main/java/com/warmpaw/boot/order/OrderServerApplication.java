package com.warmpaw.boot.order;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/** order-server 独立进程入口；启动前加载 backend/.env，默认连接现有 MySQL 与 Redis。 */
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
