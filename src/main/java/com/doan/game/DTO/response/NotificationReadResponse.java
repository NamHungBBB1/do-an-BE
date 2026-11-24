package com.doan.game.DTO.response;

import java.time.Instant;
import java.util.UUID;

/** Kết quả đánh dấu MỘT thông báo đã đọc. Gọi lại trên dòng đã đọc: readAt giữ nguyên bản đầu. */
public record NotificationReadResponse(UUID id, boolean read, Instant readAt) {
}
