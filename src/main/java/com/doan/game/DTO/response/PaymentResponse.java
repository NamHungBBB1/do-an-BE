package com.doan.game.DTO.response;

/** Vai chỉ bật khi webhook báo đã trả, không bật lúc tạo giao dịch. */
public record PaymentResponse(String checkoutUrl, String gatewayRef) {
}
