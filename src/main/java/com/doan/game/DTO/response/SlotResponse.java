package com.doan.game.DTO.response;

/** PIN không bao giờ trả về. locked tính từ lockedUntil, không phải cột status. */
public record SlotResponse(java.util.UUID id, String code, String displayName, String badge, String status, boolean locked) {
}
