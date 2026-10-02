package com.doan.game.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Khoá và URL PayOS, đọc từ app.payos.* (tức biến môi trường PAYOS_* trong /etc/finteen.env
 * trên máy chủ). Record + constructor binding: không setter, nạp xong là không ai sửa được.
 */
@ConfigurationProperties(prefix = "app.payos")
public record PayOsProperties(String clientId, String apiKey, String checksumKey,
                              String webhookUrl, String returnUrl, String cancelUrl) {

    /** Chưa đặt khoá (chạy ở máy dev) thì PaymentService không được gọi PayOS thật. */
    public boolean daCauHinh() {
        return !trong(clientId) && !trong(apiKey) && !trong(checksumKey);
    }

    private static boolean trong(String s) {
        return s == null || s.isBlank();
    }
}
