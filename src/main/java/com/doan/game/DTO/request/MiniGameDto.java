package com.doan.game.DTO.request;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Trẻ nhập gì ở MỘT ô của mini-game (lần nộp đầu). Mini-game có câu {@code grade} (khuôn v3): {@code itemId} = mã ô nhập,
 * {@code chosen} = giá trị JSON bất kỳ (chữ, số, mảng, object). Bản cũ (1.1.0): {@code itemId} = thẻ / câu, {@code chosen} = chữ.
 * BE chấm và suy khái niệm từ JSON chương — client gửi đúng / sai gì cũng bị bỏ qua.
 */
public record MiniGameDto(String gameId, String itemId, JsonNode chosen, java.time.Instant playedAt) {
}
