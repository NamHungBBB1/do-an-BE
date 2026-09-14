package com.doan.game.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS khai ĐÚNG MỘT CHỖ. Đừng rắc thêm @CrossOrigin lên controller —
 * BE của SWP làm cả hai và giờ không ai biết chỗ nào đang có hiệu lực.
 *
 * Từ khi có Spring Security, đây phải là một CorsConfigurationSource chứ không phải
 * WebMvcConfigurer: chuỗi filter của Security chạy TRƯỚC DispatcherServlet, nên cấu hình
 * kiểu WebMvc sẽ không kịp áp cho preflight và trình duyệt chặn ngay ở OPTIONS.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(allowedOrigins));
        c.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }
}
