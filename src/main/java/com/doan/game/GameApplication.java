package com.doan.game;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// @EnableAsync cho MailService: gửi mail KHÔNG được nằm trong luồng của /register.
// EXE201 từng để register treo theo SMTP vì gửi đồng bộ trong transaction.
@EnableAsync
@SpringBootApplication
public class GameApplication {
    public static void main(String[] args) {
        SpringApplication.run(GameApplication.class, args);
    }
}
