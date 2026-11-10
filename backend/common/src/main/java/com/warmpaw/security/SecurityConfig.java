package com.warmpaw.security;

import com.warmpaw.service.AuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置：无状态 JWT 认证。
 *
 * <ul>
 *   <li><b>无状态</b>：不创建 HttpSession，每个请求都靠 JWT 证明身份；
 *   <li><b>关闭表单登录、HTTP Basic、默认登出</b>：登录接口是项目自己的 {@code /auth/*}，
 *       由 {@code AuthService} 签发 JWT；
 *   <li><b>关闭 CSRF</b>：API 用 {@code Authorization} 头传 token，浏览器不会自动带上；
 *       买家网站的 HttpOnly Cookie 设为 {@code SameSite=Lax}，跨站 POST 不会带 Cookie；
 *   <li><b>不改写缓存头</b>：保持各接口原有的 Cache-Control（图片等公开文件需要被缓存）。
 * </ul>
 *
 * <p><b>授权（谁能访问哪个接口）不在这里做</b>：每个接口在 Controller 里显式声明访问级别
 * （{@code AccessLevel.BUYER / STAFF / ADMIN / PUBLIC}），由业务执行器统一校验，并且写操作
 * 拿到锁后还会二次复核。这比按 URL 前缀配置规则更精确，所以这里对所有路径放行，
 * Spring Security 只负责"认证"——解析 JWT、建立当前用户。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .requestCache(cache -> cache.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(headers -> headers.cacheControl(cache -> cache.disable()))
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .addFilterBefore(
            new JwtAuthenticationFilter(auth), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * 不使用 Spring Security 的用户名密码体系（登录由 {@code AuthService} 完成）。
   * 显式提供一个永远查不到用户的实现，避免 Spring Boot 自动创建默认用户并在日志里打印随机密码。
   */
  @Bean
  UserDetailsService userDetailsService() {
    return username -> {
      throw new UsernameNotFoundException("不支持用户名密码认证");
    };
  }
}
