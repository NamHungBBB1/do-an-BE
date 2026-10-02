package com.doan.game.DTO.response;

/** Một gói 3 tháng, tính theo ngày giờ Việt Nam: dùng hết ngày expiresOn. */
public record EntitlementResponse(java.util.UUID id, String kind, java.time.LocalDate startsOn, java.time.LocalDate expiresOn, String source) {
}
