package com.doan.game.DTO.response;

/** GET /api/quizzes/{id}/results/{slotId}: bài làm của một em cho giáo viên; chưa nộp → 404 / 8003. paper (16/11, v3): đề em đó nhận theo thứ tự em thấy; null với đề cũ. */
public record QuizSlotResultResponse(java.util.UUID slotId, int score, int totalQuestions, java.time.Instant submittedAt, java.util.List<QuizAnswerResponse> answers, java.util.List<PaperItemResponse> paper) {
}
