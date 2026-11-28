package com.doan.game.DTO.response;

/**
 * Trạng thái một giao dịch, hỏi lại PayOS theo orderCode (FE poll GET và cron dùng chung).
 *
 * <p>Đơn còn PENDING trả thêm thông tin QR/tài khoản để FE mở lại popup VietQR từ lịch sử thanh toán
 * (khi đó FE chỉ có orderCode, không có response lúc bấm Mua). Tên field khớp response tạo đơn:
 * {@code bin}, {@code accountNumber}, {@code accountName}, {@code description}, {@code expiresAt}.
 */
public record PaymentStatusResponse(long orderCode, String status, long amount, java.time.Instant paidAt,
                                    String qrCode, String bin, String accountNumber, String accountName,
                                    String description, java.time.Instant expiresAt) {
}
