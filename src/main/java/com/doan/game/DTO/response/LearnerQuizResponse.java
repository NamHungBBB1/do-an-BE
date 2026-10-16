package com.doan.game.DTO.response;

/** GET /api/learner/quizzes/{id}: đề để làm, KHÔNG có correctIndex; submission null khi chưa nộp. */
public record LearnerQuizResponse(java.util.UUID id, String kind, Integer chapter, String title, java.time.Instant dueAt, java.util.List<LearnerQuestionResponse> questions, LearnerQuizCompletedResponse submission) {
}
