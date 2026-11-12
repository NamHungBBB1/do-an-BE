package com.doan.game.DTO.response;

/**
 * Chi tiết một giao dịch cho màn quản trị: như một dòng trong bảng, thêm dữ liệu chỉ màn chi tiết cần
 * (mã tham chiếu cổng, nội dung chuyển khoản, hạn của link QR).
 */
public record TransactionDetailResponse(long orderCode, java.util.UUID accountId, String email, String kind,
                                        String purpose, long amount, Integer months, String status,
                                        java.time.Instant createdAt, java.time.Instant paidAt,
                                        boolean needsReview, String note,
                                        String gatewayRef, String transferNote, java.time.Instant expiresAt) {
}
