package com.doan.game.DTO.response;

/** slotUsed đếm slot ACTIVE — ARCHIVED / WIPED không ăn chỗ. */
public record LearnerGroupResponse(java.util.UUID id, String name, String context, int slotLimit, int slotUsed, boolean consentConfirmed) {
}
