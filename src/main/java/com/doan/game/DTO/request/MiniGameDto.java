package com.doan.game.DTO.request;

/** Trẻ nhập gì ở MỘT ô của mini-game (lần nộp đầu). Khuôn v3: itemId = mã ô nhập, chosen = JSON bất kỳ; bản 1.1.0: itemId = thẻ / câu, chosen = chữ. BE chấm và suy khái niệm từ JSON chương — client gửi đúng / sai gì cũng bị bỏ qua (#47). */
public record MiniGameDto(String gameId, String itemId, com.fasterxml.jackson.databind.JsonNode chosen, java.time.Instant playedAt) {
}
