package com.gitchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * GitChat 主启动类 —— 后端的"总机"（相当于 Python 的 main.py）
 * 
 * @SpringBootApplication 一个注解干三件事：
 *   1. 告诉SpringBoot："这是启动类"
 *   2. 自动扫描当前包下所有组件（Controller、Service等）
 *   3. 启用自动配置（数据库、JSON转换、Web服务器...）
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        System.out.println("==========================================");
        System.out.println("  GitChat (Spring Boot) 已启动！");
        System.out.println("  http://localhost:8000");
        System.out.println("==========================================");
    }
}
