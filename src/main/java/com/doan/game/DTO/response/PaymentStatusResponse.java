package com.doan.game.DTO.response;

/** Trạng thái một giao dịch, hỏi lại PayOS theo orderCode (FE poll GET và cron dùng chung). */
public record PaymentStatusResponse(long orderCode, String status, long amount, java.time.Instant paidAt) {
}
