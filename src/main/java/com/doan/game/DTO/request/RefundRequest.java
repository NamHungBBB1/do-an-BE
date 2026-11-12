package com.doan.game.DTO.request;

/**
 * Yêu cầu ghi nhận hoàn tiền một đơn. Hệ thống chỉ ghi nhận, tiền chuyển ngoài app.
 * {@code amount} bỏ trống = hoàn toàn bộ giá trị đơn; {@code reason} bắt buộc.
 */
public record RefundRequest(Long amount, String reason) {
}
