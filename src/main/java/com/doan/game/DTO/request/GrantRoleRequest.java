package com.doan.game.DTO.request;

/** Admin phát hoặc thu vai. `reason` bắt buộc — không có lý do thì từ chối. */
public record GrantRoleRequest(java.util.UUID subjectId, String role, String reason) {
}
