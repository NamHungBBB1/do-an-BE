package com.doan.game.DTO.response;

/** FE vẽ QR từ qrCode (VietQR) ngay trong app, không chuyển sang trang PayOS (Hưng chốt 07/10); gói chỉ bật khi đã trả. */
public record PaymentResponse(long orderCode, String qrCode, String bin, String accountNumber, String accountName, long amount, String description, java.time.Instant expiresAt, String checkoutUrl) {
}
