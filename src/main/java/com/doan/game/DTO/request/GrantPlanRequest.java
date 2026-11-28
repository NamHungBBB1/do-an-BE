package com.doan.game.DTO.request;

/**
 * Admin cấp gói gốc không cần thanh toán (dùng thử, đền bù). Từ 28/11/2026 gói cấp tay cũng
 * **vĩnh viễn** như gói mua nên KHÔNG còn {@code months}; {@code reason} bắt buộc.
 */
public record GrantPlanRequest(java.util.UUID accountId, String kind, String reason) {
}
