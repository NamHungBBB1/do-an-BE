package com.doan.game.DTO.response;

/** PIN không bao giờ trả về; chỉ trả mã QR lúc vừa phát. */
public record SlotResponse(java.util.UUID id, String code, String displayName, String badge, boolean archived) {
}
