package com.doan.game.DTO.response;

import com.doan.game.enums.NotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng chuông thông báo. title / message được ghép LÚC ĐỌC (không lưu trong DB) để xoá slot là
 * xoá tên thật; slotDisplayName null nghĩa là slot đã bị xoá (WIPED) hoặc dòng gộp của giáo viên.
 */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String message,
        UUID slotId,
        String slotDisplayName,
        UUID groupId,
        Integer chapter,
        UUID quizId,
        boolean read,
        Instant readAt,
        Instant createdAt,
        NotificationAction action) {

    /** Gợi ý điều hướng cho FE; BE không trả URL tuyệt đối. */
    public record NotificationAction(String type, UUID resourceId) {
    }
}
