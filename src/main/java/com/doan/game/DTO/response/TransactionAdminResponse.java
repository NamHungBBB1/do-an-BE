package com.doan.game.DTO.response;

/** Một dòng trong bảng giao dịch của admin (và lịch sử của tôi). needsReview + note: đơn lệch tiền / trả muộn cần đối soát (P-01, 10/10). */
public record TransactionAdminResponse(long orderCode, java.util.UUID accountId, String email, String kind, String purpose, long amount, Integer months, String status, java.time.Instant createdAt, java.time.Instant paidAt, boolean needsReview, String note) {
}
