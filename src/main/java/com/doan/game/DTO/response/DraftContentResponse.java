package com.doan.game.DTO.response;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Nháp mới nhất của một chương KÈM nội dung (23/10): Editor tải lại để sửa tiếp, Reviewer chơi thử trước khi duyệt.
 * content đúng hình dạng chapterNV2GameData, {{ASSET_BASE}} đã thay bằng tiền tố kho file (lưu lại nguyên vậy được —
 * BE tự đổi tiền tố về placeholder khi lưu).
 */
public record DraftContentResponse(DraftSummaryResponse draft, JsonNode content) {
}
