package com.lxf.tinyclaw;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 启动类。启动后直接委托给原始入口 {@link Claw#main(String[])}，保持原有逻辑不变。
 */
@SpringBootApplication
public class TinyClawApplication implements CommandLineRunner {

    public static void main(String[] args) {
        SpringApplication.run(TinyClawApplication.class, args);
    }

    @Override
    public void run(String... args) {
        Claw.main(args);
    }
}
