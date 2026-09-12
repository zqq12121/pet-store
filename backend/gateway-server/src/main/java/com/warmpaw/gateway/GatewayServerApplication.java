package com.warmpaw.gateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** 唯一前端 API 入口，通过 Nacos 发现业务服务；网关不连接业务数据库。 */
@SpringBootApplication
public class GatewayServerApplication {
  public static void main(String[] args) { SpringApplication.run(GatewayServerApplication.class, args); }
}
