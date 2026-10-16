package com.doan.game.DTO.response;

/** GET /api/quizzes/{id}: đề kèm câu hỏi đầy đủ cho giáo viên (có correctIndex). */
public record QuizDetailResponse(QuizResponse quiz, java.util.List<QuestionResponse> questions) {
}
