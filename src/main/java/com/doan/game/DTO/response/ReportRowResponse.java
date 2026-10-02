package com.doan.game.DTO.response;

/** displayName lấy live từ LearnerSlot — em đã xoá sạch thì hiện tên mặc định. */
public record ReportRowResponse(java.util.UUID slotId, String displayName, String figures) {
}
