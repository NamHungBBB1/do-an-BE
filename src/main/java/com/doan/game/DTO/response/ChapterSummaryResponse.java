package com.doan.game.DTO.response;

/** Một dòng trong danh sách chương của một bản phát hành (không kèm nội dung — nội dung lấy theo từng chương). */
public record ChapterSummaryResponse(String code, int chapterNumber, String title) {
}
