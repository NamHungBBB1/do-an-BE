package com.doan.game.DTO.response;

/** slotUsed đếm slot ACTIVE — ARCHIVED / WIPED không ăn chỗ. closedAt null = nhóm đang mở. postTestEnabled (16/10): lớp đang bật hậu trắc (postTestEnabledAt != null); FAMILY luôn false. */
public record LearnerGroupResponse(java.util.UUID id, String name, String context, int slotLimit, int slotUsed, boolean consentConfirmed, java.time.Instant closedAt, boolean postTestEnabled) {
}
