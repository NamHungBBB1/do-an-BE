package com.doan.game.DTO.response;

/** Một dòng danh sách mọi tài khoản cho trang phân quyền (GET /api/admin/accounts/list, Hưng 25/10). */
public record AdminAccountResponse(java.util.UUID id, String email, String displayName, java.util.List<String> plans, java.util.List<String> roles, java.time.Instant createdAt, java.util.List<AdminSlotResponse> slots) {
}
