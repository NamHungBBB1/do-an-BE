package com.doan.game.DTO.request;

/**
 * Sửa sĩ số lớp (17/11/2026). Chỉ lớp CLASS đang mở. Không nhỏ hơn số slot đang ACTIVE và không vượt
 * quá ví còn lại (sĩ số tương lai = ACTIVE + số slot được mở thêm).
 */
public record UpdateGroupRequest(Integer plannedSize) {
}
