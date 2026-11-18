package com.doan.game.DTO.request;

/**
 * PATCH /api/slots/{id} (Q18, 18/11/2026): đổi tên, kèm PIN mới nếu muốn. {@code displayName} bắt
 * buộc, không trùng slot ACTIVE khác trong nhóm; {@code pin} bỏ trống = giữ PIN cũ.
 */
public record UpdateSlotRequest(String displayName, String pin) {
}
