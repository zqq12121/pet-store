package com.warmpaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 单店业务后端；AI 和知识库不在本期实现范围。 */
@SpringBootApplication
@EnableScheduling
public class WarmPawApplication {
  public static void main(String[] args) {
    SpringApplication.run(WarmPawApplication.class, args);
  }
}
