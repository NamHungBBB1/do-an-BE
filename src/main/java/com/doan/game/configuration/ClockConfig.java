package com.doan.game.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Một đồng hồ dùng chung, cố định múi giờ Việt Nam. Hạn gói tính theo NGÀY (startsOn / expiresOn),
 * nên "hôm nay" phải là hôm nay ở Việt Nam chứ không phải UTC của máy chủ; và test thay được bằng
 * Clock.fixed để kiểm quy tắc ngày mà không phụ thuộc lúc chạy.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Bean
    Clock clock() {
        return Clock.system(VN);
    }
}
