package com.doan.game.DTO.response;

/** Web hoặc app mở checkoutUrl. Gói chỉ bật khi webhook báo đã trả, không bật lúc tạo giao dịch. */
public record PaymentResponse(String checkoutUrl, long orderCode) {
}
