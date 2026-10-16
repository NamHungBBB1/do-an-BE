package com.doan.game.DTO.response;

/** Đề em đã nộp, kèm điểm. */
public record LearnerQuizCompletedResponse(java.util.UUID id, String kind, Integer chapter, String title, int score, int totalQuestions, java.time.Instant submittedAt) {
}
