package com.doan.game.DTO.request;

/** Câu trả lời đầu tiên cho một thẻ / câu mini-game; BE chấm correct và suy concept từ JSON chương. */
public record MiniGameDto(String gameId, String itemId, String chosen, java.time.Instant playedAt) {
}
