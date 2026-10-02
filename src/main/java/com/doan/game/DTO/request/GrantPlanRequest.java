package com.doan.game.DTO.request;

/** Admin cấp gói không cần thanh toán (dùng thử, đền bù). `reason` bắt buộc. */
public record GrantPlanRequest(java.util.UUID accountId, String kind, int months, String reason) {
}
