package com.doan.game.DTO.response;

/** Kết quả đọc tất cả: số dòng vừa đổi và số chưa đọc còn lại (luôn 0). */
public record NotificationReadAllResponse(int updatedCount, long unreadCount) {
}
