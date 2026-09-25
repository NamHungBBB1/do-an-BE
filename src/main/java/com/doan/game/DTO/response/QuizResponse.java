package com.doan.game.DTO.response;

/** Một đề quiz. */
public record QuizResponse(java.util.UUID id, String title, int soCau, java.time.Instant publishedAt) {
}
