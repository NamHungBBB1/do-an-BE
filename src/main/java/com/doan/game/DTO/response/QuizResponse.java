package com.doan.game.DTO.response;

/** Một đề quiz đã phát cho một lớp. */
public record QuizResponse(java.util.UUID id, java.util.UUID groupId, String title, int questionCount, java.time.Instant dueAt, java.time.Instant publishedAt) {
}
