package com.doan.game.DTO.response;

import java.util.List;

/**
 * Gợi ý cách đọc cho giọng đọc (spec build/spec-kho-anh-giong-2026-10-24.md mục 4.7.2): KHÔNG lưu — người soạn bấm Nhận thì Studio
 * ghi {@code spokenText} vào nháp. {@code source} / {@code model} = AI nào sinh ra (null khi không cần gọi AI).
 * {@code lines} = số câu thoại có người nói; {@code sentToAi} = số câu lọc ra có thứ dễ đọc sai và đã gửi AI;
 * {@code aiCalls} = số lần gọi AI (thường 1, 0 khi không có câu nào cần). Chỉ câu cần đọc khác chữ hiển thị mới có trong
 * {@code suggestions}.
 */
public record VoiceSuggestResponse(String source, String model, int lines, int sentToAi, int aiCalls, List<Item> suggestions) {

    public record Item(String sceneId, String text, String suggestion, List<String> reasons) {
    }
}
