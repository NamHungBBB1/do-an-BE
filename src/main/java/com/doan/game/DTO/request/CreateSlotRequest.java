package com.doan.game.DTO.request;

/** Người lớn đặt tên và PIN cho chỗ ngồi. Trẻ không tự gõ tên mình. */
public record CreateSlotRequest(String displayName, String pin) {
}
