package com.doan.game.DTO.response;

/** Vai suy ra từ gói đang giữ, không phải một cột role. */
public record AccountResponse(java.util.UUID id, String email, String displayName, boolean parentPlan, boolean teacherPlan) {
}
