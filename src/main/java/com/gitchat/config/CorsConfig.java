package com.gitchat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置 —— 相当于 Python 的 CORSMiddleware
 * 让前端（localhost:3000）能访问后端（localhost:8000）
 * "*" = 允许所有人访问
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")           // 所有路径
                        .allowedOrigins("*")          // 允许所有来源
                        .allowedMethods("*")          // 允许所有方法(GET/POST/...)
                        .allowedHeaders("*");         // 允许所有请求头
            }
        };
    }
}
