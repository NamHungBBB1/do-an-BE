package com.doan.game.DTO.response;

/** GET /api/quizzes/{id}/results: kết quả cả lớp cho giáo viên. */
public record QuizResultsResponse(QuizResponse quiz, QuizResultsSummaryResponse summary, java.util.List<QuizResultResponse> rows) {
}
