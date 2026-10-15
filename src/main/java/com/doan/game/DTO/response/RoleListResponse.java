package com.doan.game.DTO.response;

/** Vai nội bộ hiện có của một tài khoản, trả sau khi admin cấp / thu (PR A, 15/10). */
public record RoleListResponse(java.util.UUID subjectId, java.util.List<String> roles) {
}
