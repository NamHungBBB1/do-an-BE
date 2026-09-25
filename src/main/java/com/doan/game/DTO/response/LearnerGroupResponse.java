package com.doan.game.DTO.response;

/** slotUsed đếm slot CHƯA archived VÀ CHƯA deleted — bia mộ không ăn chỗ. */
public record LearnerGroupResponse(java.util.UUID id, String context, String name, int slotLimit, int slotUsed) {
}
