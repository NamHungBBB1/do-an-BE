package com.doan.game.DTO.response;

/** Trẻ là ai, thuộc gia đình (FAMILY) hay lớp (CLASS) — token SLOT không gọi được /auth/me (Triệu xin, Hưng duyệt 08/10). */
public record SlotProfileResponse(java.util.UUID id, String displayName, String badge, String groupContext) {
}
