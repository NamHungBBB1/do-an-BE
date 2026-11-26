package com.doan.game.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Gửi push cho mọi thiết bị của một tài khoản, SAU khi transaction nghiệp vụ commit.
 *
 * <p>Hoãn tới sau commit vì hai lý do: (1) transaction rollback thì không được để lại tin đã đẩy;
 * (2) không giữ transaction DB trong lúc chờ mạng tới FCM.
 *
 * <p>Danh sách token đọc NGAY trong transaction đang mở, không đọc lại trong afterCompletion: lúc
 * đó transaction cũ đã commit nhưng tài nguyên còn buộc vào thread, đọc/ghi DB ở đó sẽ "tham gia"
 * transaction đã kết thúc và không được commit (xem javadoc TransactionSynchronization). Vì vậy chỉ
 * có thao tác xoá token chết mới mở transaction mới (REQUIRES_NEW trong DeviceTokenService).
 *
 * <p>Push là phụ: mọi lỗi ở đây chỉ ghi log, không được làm hỏng response của luồng chính (chuông
 * trong app vẫn còn nguyên).
 */
@Component
public class PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);

    private final PushService pushService;
    private final DeviceTokenService deviceTokens;

    public PushNotifier(PushService pushService, DeviceTokenService deviceTokens) {
        this.pushService = pushService;
        this.deviceTokens = deviceTokens;
    }

    /**
     * Đẩy một tin cho tài khoản. Gọi trong transaction thì gửi sau commit; gọi ngoài transaction
     * (test, job) thì gửi ngay.
     */
    public void notify(UUID accountId, PushMessage message) {
        if (accountId == null || message == null || !pushService.isEnabled()) {
            return;
        }
        List<String> tokens = readTokens(accountId);
        if (tokens.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        deliver(tokens, message);
                    }
                }
            });
        } else {
            deliver(tokens, message);
        }
    }

    private List<String> readTokens(UUID accountId) {
        try {
            return deviceTokens.tokensOf(accountId);
        } catch (RuntimeException e) {
            log.warn("Push: không đọc được token của tài khoản {}", accountId, e);
            return List.of();
        }
    }

    private void deliver(List<String> tokens, PushMessage message) {
        try {
            for (String token : tokens) {
                if (pushService.send(token, message) == PushService.Outcome.INVALID_TOKEN) {
                    deviceTokens.removeToken(token);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Push: không gửi được tin '{}'", message.title(), e);
        }
    }
}
