package com.doan.game.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Gửi push notification qua FCM HTTP v1, dùng FirebaseApp tên {@code push} của project mobile.
 *
 * <p>Cùng cách MailService xử lý SMTP: không có cấu hình thì KHÔNG gọi ra ngoài. Máy dev, CI và test
 * H2 không đặt FIREBASE_PUSH_CREDENTIALS nên {@link #isEnabled()} trả false và mọi lời gọi
 * {@link #send} trả {@link Outcome#DISABLED} — không ném, vì push là phụ: hỏng push không được làm
 * hỏng luồng tạo thông báo (giống mail, chuông trong app vẫn còn).
 *
 * <p>Đã nối vào luồng nghiệp vụ: NotificationServiceImpl dựng {@link PushMessage} từ đúng câu chữ của
 * chuông, {@link PushNotifier} gửi cho mọi token của tài khoản nhận (bảng {@code device_token}) sau
 * khi transaction commit. Token chết (FCM trả UNREGISTERED) thì chỗ gọi xoá luôn dòng đó.
 */
@Service
public class PushService {

    /**
     * Kết quả một lần gửi. INVALID_TOKEN = token chết (FCM: UNREGISTERED / SENDER_ID_MISMATCH),
     * chỗ gọi nên xoá khỏi device_token. Lỗi khác — kể cả INVALID_ARGUMENT do nội dung tin sai —
     * là FAILED: KHÔNG xoá token.
     */
    public enum Outcome {
        SENT,
        INVALID_TOKEN,
        DISABLED,
        FAILED
    }

    /**
     * Kênh thông báo trên Android. App phải tạo channel cùng id này lúc khởi động
     * (Notifications.setNotificationChannelAsync('default', ...)), nếu không Android 8+ sẽ bỏ tin.
     */
    public static final String ANDROID_CHANNEL_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final ObjectProvider<FirebaseApp> pushApp;

    public PushService(@Qualifier("pushFirebaseApp") ObjectProvider<FirebaseApp> pushApp) {
        this.pushApp = pushApp;
    }

    /** Có khoá và đã khởi tạo được FirebaseApp chưa. */
    public boolean isEnabled() {
        return pushApp.getIfAvailable() != null;
    }

    /**
     * Gửi một tin cho một thiết bị. Không ném exception: lỗi mạng/phía Google chỉ ghi log và trả
     * {@link Outcome#FAILED}; nội dung tin trong app vẫn còn nguyên.
     */
    public Outcome send(String deviceToken, PushMessage message) {
        FirebaseApp app = pushApp.getIfAvailable();
        if (app == null) {
            return Outcome.DISABLED;
        }
        if (deviceToken == null || deviceToken.isBlank() || message == null) {
            return Outcome.FAILED;
        }

        Message request = Message.builder()
                .setToken(deviceToken)
                .setNotification(Notification.builder()
                        .setTitle(message.title())
                        .setBody(message.body())
                        .build())
                .putAllData(message.data())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setChannelId(ANDROID_CHANNEL_ID)
                                .build())
                        .build())
                .build();

        try {
            FirebaseMessaging.getInstance(app).send(request);
            return Outcome.SENT;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            Outcome outcome = classify(code);
            if (outcome == Outcome.INVALID_TOKEN) {
                log.info("Push: token không còn dùng được ({}), cần xoá khỏi device_token", code);
            } else {
                log.warn("Push: gửi thất bại ({})", code, e);
            }
            return outcome;
        } catch (IllegalArgumentException e) {
            // Token rỗng/sai định dạng bị SDK chặn trước khi gọi mạng.
            log.info("Push: token sai định dạng, cần xoá khỏi device_token");
            return Outcome.INVALID_TOKEN;
        }
    }

    /**
     * Chỉ UNREGISTERED và SENDER_ID_MISMATCH mới chắc chắn là token hỏng. INVALID_ARGUMENT còn do
     * NỘI DUNG tin sai (data trùng khoá dành riêng của FCM, payload quá lớn...). Coi nó là token
     * chết sẽ xoá sạch token hợp lệ của mọi người nhận chỉ vì một tin dựng lỗi, và app chỉ đăng ký
     * lại ở lần mở sau.
     */
    static Outcome classify(MessagingErrorCode code) {
        if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
            return Outcome.INVALID_TOKEN;
        }
        return Outcome.FAILED;
    }
}
