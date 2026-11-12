package com.doan.game.DTO.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Chi tiết một giao dịch cho màn quản trị: như một dòng trong bảng, thêm dữ liệu chỉ màn chi tiết cần
 * (mã tham chiếu cổng, nội dung chuyển khoản, hạn của link QR) và thông tin hoàn tiền.
 */
public record TransactionDetailResponse(long orderCode, UUID accountId, String email, String kind,
                                        String purpose, long amount, Integer months, String status,
                                        Instant createdAt, Instant paidAt,
                                        boolean needsReview, String note,
                                        String gatewayRef, String transferNote, Instant expiresAt,
                                        Instant refundedAt, Long refundAmount, String refundReason,
                                        String refundedByEmail) {
}
