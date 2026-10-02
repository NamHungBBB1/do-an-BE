package com.doan.game.DTO.request;

/** Giáo viên dán danh sách lớp, mở nhiều slot một lần; vượt hạn mức thì từ chối cả lô. */
public record CreateSlotsRequest(java.util.List<CreateSlotRequest> slots) {
}
