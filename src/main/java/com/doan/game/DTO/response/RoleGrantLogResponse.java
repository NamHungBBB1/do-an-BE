package com.doan.game.DTO.response;

/** Một dòng sổ cấp / thu vai nội bộ: ai làm, với ai, vai gì, cấp hay thu, lý do, lúc nào (GET /api/admin/roles/log, 16/10). */
public record RoleGrantLogResponse(java.util.UUID id, java.util.UUID actorId, String actorEmail, java.util.UUID targetId, String targetEmail, String role, boolean granted, String reason, java.time.Instant createdAt) {
}
