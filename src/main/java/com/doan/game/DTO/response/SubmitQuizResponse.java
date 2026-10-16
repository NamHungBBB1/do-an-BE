package com.doan.game.DTO.response;

/** Trả sau POST /api/learner/quizzes/{id}/submit. score = số câu đúng (INT), không phần trăm. */
public record SubmitQuizResponse(java.util.UUID quizId, int score, int totalQuestions, java.time.Instant submittedAt, java.util.List<AnswerReviewResponse> review) {
}
