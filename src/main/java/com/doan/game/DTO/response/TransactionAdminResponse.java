package com.doan.game.DTO.response;

/** Một dòng trong bảng giao dịch của admin. */
public record TransactionAdminResponse(long orderCode, java.util.UUID accountId, String email, String kind, String purpose, long amount, String status, java.time.Instant createdAt, java.time.Instant paidAt) {
}
