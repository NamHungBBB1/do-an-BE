package com.doan.game.DTO.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng trong bảng giao dịch của admin (và lịch sử của tôi). needsReview + note: đơn lệch tiền / trả muộn
 * cần đối soát (P-01, 10/10). Bốn trường hoàn tiền: "đã hoàn" = refundedAt != null.
 */
public record TransactionAdminResponse(long orderCode, UUID accountId, String email, String kind, String purpose,
                                       long amount, Integer months, String status, Instant createdAt, Instant paidAt,
                                       boolean needsReview, String note,
                                       Instant refundedAt, Long refundAmount, String refundReason,
                                       String refundedByEmail) {
}
