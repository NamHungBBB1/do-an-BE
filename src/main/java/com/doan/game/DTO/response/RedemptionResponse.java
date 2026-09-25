package com.doan.game.DTO.response;

/** Chỉ tiền trong game, không bao giờ tiền thật. */
public record RedemptionResponse(java.util.UUID id, String itemName, int cost, java.time.Instant redeemedAt) {
}
