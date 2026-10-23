package com.doan.game.DTO.response;

import java.util.List;

/**
 * Lưu nháp xong (24/10, K1): như ChapterSummaryResponse + cảnh báo của bộ khuôn — dạng CŨ được chấp nhận tạm (điều kiện chuỗi,
 * mini-game thiếu kind, chỉ số ngoài khuôn…). Studio hiện danh sách này để editor chuyển dần; lỗi thật thì 400 / 1001, không tới đây.
 */
public record DraftSavedResponse(String code, int chapterNumber, String title, List<String> warnings) {
}
