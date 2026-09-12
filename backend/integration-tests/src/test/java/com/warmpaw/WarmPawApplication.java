package com.warmpaw;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
/** 仅测试聚合上下文，复用原交易回归；生产服务入口和 Feign 转发不在此处装配。 */
@SpringBootConfiguration
@EnableAutoConfiguration
@org.mybatis.spring.annotation.MapperScan("com.warmpaw.mapper")
@ComponentScan(basePackages = "com.warmpaw", excludeFilters = @ComponentScan.Filter(
    type = FilterType.REGEX, pattern = {"com.warmpaw.boot.*", "com.warmpaw.remote.*"}))
public class WarmPawApplication {}
