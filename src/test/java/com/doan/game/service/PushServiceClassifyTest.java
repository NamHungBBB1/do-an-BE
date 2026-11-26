package com.doan.game.service;

import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phân loại lỗi FCM (26/11, sau soát PR #81): chỉ token hỏng thật mới được xoá khỏi device_token.
 * INVALID_ARGUMENT còn do nội dung tin sai — xoá token vì nó là xoá oan cả danh sách người nhận.
 */
class PushServiceClassifyTest {

    @Test
    @DisplayName("Chỉ UNREGISTERED và SENDER_ID_MISMATCH là token chết")
    void onlyDeadTokenCodesAreInvalidToken() {
        assertEquals(PushService.Outcome.INVALID_TOKEN,
                PushService.classify(MessagingErrorCode.UNREGISTERED));
        assertEquals(PushService.Outcome.INVALID_TOKEN,
                PushService.classify(MessagingErrorCode.SENDER_ID_MISMATCH));
    }

    @Test
    @DisplayName("Lỗi nội dung / mạng / không rõ mã là FAILED, không xoá token")
    void otherErrorsDoNotMarkTheTokenDead() {
        assertEquals(PushService.Outcome.FAILED,
                PushService.classify(MessagingErrorCode.INVALID_ARGUMENT));
        assertEquals(PushService.Outcome.FAILED,
                PushService.classify(MessagingErrorCode.UNAVAILABLE));
        assertEquals(PushService.Outcome.FAILED,
                PushService.classify(MessagingErrorCode.INTERNAL));
        assertEquals(PushService.Outcome.FAILED, PushService.classify(null));
    }
}
