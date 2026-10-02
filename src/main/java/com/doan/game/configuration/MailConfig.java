package com.doan.game.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Đăng ký cấu hình mail (app.mail.*). Không tạo JavaMailSender ở đây — Spring Boot tự tạo khi có
 * spring.mail.host; MailService kiểm tra cả hai nên máy dev không có SMTP thì chỉ in log.
 *
 * Lớp này tồn tại chỉ để đăng ký AppMailProperties, cùng kiểu PayOsConfig làm cho PayOsProperties.
 */
@Configuration
@EnableConfigurationProperties(AppMailProperties.class)
public class MailConfig {
}