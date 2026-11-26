package com.doan.game.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pool riêng cho việc gửi push.
 *
 * <p>FCM là lời gọi mạng ra ngoài: chạy trên thread của request (bé nộp bài, hàng đợi AI) thì mạng
 * chậm làm request chậm theo. afterCompletion chỉ đẩy việc sang pool này rồi trả về ngay.
 *
 * <p>Hai thread là đủ: mỗi tin chỉ là một request HTTP ngắn, đã có timeout 5 giây ở
 * {@link FirebasePushConfig}. Thread là daemon để không giữ JVM sống khi tắt ứng dụng.
 */
@Configuration
public class PushExecutorConfig {

    @Bean(name = "pushExecutor", destroyMethod = "shutdown")
    ExecutorService pushExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "push-notify");
            thread.setDaemon(true);
            return thread;
        });
    }
}
