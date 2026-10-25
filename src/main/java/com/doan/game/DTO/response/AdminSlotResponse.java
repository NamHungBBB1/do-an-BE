package com.doan.game.DTO.response;

/** Slot của một tài khoản cho màn admin (25/10): KHÔNG có tên, mã đăng nhập, huy hiệu của trẻ — chỉ trạng thái và chơi tới đâu (chương đã xong, lần chơi, lần chơi cuối; chưa chơi = rỗng / null). */
public record AdminSlotResponse(java.util.UUID id, java.util.UUID groupId, String context, String status, boolean locked, java.time.Instant createdAt, java.util.List<Integer> chaptersDone, Integer attemptIndex, java.time.Instant lastPlayedAt) {
}
