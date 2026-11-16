package com.doan.game.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Nguồn ngẫu nhiên xáo đề quiz v3 (mỗi em một đề riêng). SecureRandom để em không đoán được thứ tự từ lần mở trước;
 * BE lưu chính bản đã xáo (paper), không lưu seed. Test thay bằng new Random(42) để kiểm được tất định.
 */
@Configuration
public class QuizConfig {

    @Bean
    Random quizRandom() {
        return new SecureRandom();
    }
}
