package com.doan.game.DTO.response;

/** plans = gói còn hạn (PARENT / TEACHER, có thể cả hai); roles = vai nội bộ (ADMIN...). */
public record AccountResponse(java.util.UUID id, String email, String displayName, java.util.List<String> plans, java.util.List<String> roles) {
}
