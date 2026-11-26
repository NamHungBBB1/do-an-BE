package com.doan.game.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Khoá gửi push notification, đọc từ app.push.* (biến môi trường FIREBASE_PUSH_* trên máy chủ).
 *
 * Đây là project Firebase RIÊNG của app mobile, KHÔNG phải {@code app.firebase.project-id} — project
 * kia chỉ dùng để kiểm ID token Google của web. Token FCM do project nào cấp thì chỉ service account
 * của project đó gửi được; trộn hai project là lỗi SENDER_ID_MISMATCH.
 *
 * Một trong hai đường nạp khoá là đủ:
 *   credentials      = nội dung file service account JSON, base64 (dùng cho Render/VPS, env là chuỗi)
 *   credentialsPath  = đường dẫn file JSON trên máy (dùng cho máy dev, file nằm ngoài repo)
 *
 * Rỗng cả hai = tắt push. Máy dev, CI và test H2 không có khoá vẫn khởi động bình thường.
 */
@ConfigurationProperties(prefix = "app.push")
public record AppPushProperties(String projectId, String credentials, String credentialsPath) {

    public boolean isConfigured() {
        return !isBlank(credentials) || !isBlank(credentialsPath);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
