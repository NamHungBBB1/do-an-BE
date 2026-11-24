package com.doan.game.DTO.response;

import java.util.List;

/**
 * Trang thông báo kèm số chưa đọc của TOÀN bộ hộp thư (không phải của riêng trang): FE vẽ badge
 * chuông bằng một lần gọi danh sách.
 */
public record NotificationListResponse(
        List<NotificationResponse> items,
        long totalElements,
        int totalPages,
        int page,
        int size,
        long unreadCount) {
}
