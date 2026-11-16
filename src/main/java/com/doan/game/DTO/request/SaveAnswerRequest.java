package com.doan.game.DTO.request;

/** Tự lưu một câu của lượt làm (16/11, v3): choice = vị trí đáp án em THẤY (đã xáo), null = bỏ chọn; leaveCount = số lần rời trang máy trẻ đếm, BE giữ số lớn nhất. */
public record SaveAnswerRequest(Integer choice, Integer leaveCount) {
}
