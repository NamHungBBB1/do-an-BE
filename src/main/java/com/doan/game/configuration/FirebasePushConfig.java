package com.doan.game.configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Khởi tạo FirebaseApp tên {@value #PUSH_APP_NAME} cho push notification, từ service account của
 * project Firebase mobile.
 *
 * Vì sao là app có tên chứ không phải app mặc định: Admin SDK cho phép nhiều FirebaseApp trong cùng
 * JVM, mỗi app một project + một khoá. Dùng app mặc định thì sau này thêm provider khác (hoặc đổi
 * project) sẽ đụng nhau, và không thể vừa verify ID token của project web vừa gửi FCM của project
 * mobile bằng hai khoá khác nhau.
 *
 * Bean chỉ tồn tại khi có khoá (xem {@link AppPushProperties}). Thiếu khoá thì PushService tự tắt —
 * đúng kiểu PayOsConfig: máy dev không có cấu hình thì không gọi ra ngoài, app vẫn khởi động.
 */
@Configuration
@EnableConfigurationProperties(AppPushProperties.class)
public class FirebasePushConfig {

    /** Tên app Admin SDK. Đừng đổi: PushService và mọi chỗ gọi đều lấy theo tên này. */
    public static final String PUSH_APP_NAME = "push";

    private static final Logger log = LoggerFactory.getLogger(FirebasePushConfig.class);

    /**
     * Chặn thời gian chờ FCM: lời gọi send chạy trên thread của request (sau commit), mạng treo mà
     * không có timeout thì học sinh nộp bài phải chờ theo. Admin SDK mặc định không giới hạn.
     */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    @Bean(name = "pushFirebaseApp")
    @ConditionalOnExpression(
            "!'${app.push.credentials:}'.isEmpty() or !'${app.push.credentials-path:}'.isEmpty()")
    FirebaseApp pushFirebaseApp(AppPushProperties props) throws IOException {
        // Test Spring có thể dựng nhiều ApplicationContext trong một JVM; initializeApp lần thứ hai
        // cùng tên sẽ ném IllegalStateException. Khoá đã nạp rồi thì dùng lại, không nạp lại.
        Optional<FirebaseApp> existing = FirebaseApp.getApps().stream()
                .filter(app -> PUSH_APP_NAME.equals(app.getName()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        if (props.projectId() == null || props.projectId().isBlank()) {
            // Có khoá mà thiếu mã project là cấu hình sai: im lặng sửa lỗi này sẽ khiến push đi nhầm
            // project của service account và lỗi chỉ hiện ra lúc gửi thật.
            throw new IllegalStateException(
                    "Đã có FIREBASE_PUSH_CREDENTIALS nhưng thiếu FIREBASE_PUSH_PROJECT_ID");
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials(props))
                .setProjectId(props.projectId().trim())
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .build();
        FirebaseApp app = FirebaseApp.initializeApp(options, PUSH_APP_NAME);
        log.info("Push notification: đã bật, project {}", props.projectId().trim());
        return app;
    }

    /** Chọn nguồn khoá: base64 trong env (máy chủ) trước, rồi tới file trên máy (dev). */
    private static GoogleCredentials credentials(AppPushProperties props) throws IOException {
        if (props.credentials() != null && !props.credentials().isBlank()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(props.credentials().trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("FIREBASE_PUSH_CREDENTIALS không phải base64 hợp lệ", e);
            }
            return GoogleCredentials.fromStream(new ByteArrayInputStream(decoded));
        }
        Path path = Path.of(props.credentialsPath().trim());
        if (!Files.isReadable(path)) {
            throw new IllegalStateException(
                    "Không đọc được FIREBASE_PUSH_CREDENTIALS_PATH: " + path);
        }
        try (InputStream in = Files.newInputStream(path)) {
            return GoogleCredentials.fromStream(in);
        }
    }
}
