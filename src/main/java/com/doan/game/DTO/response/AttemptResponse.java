package com.doan.game.DTO.response;

/** POST /api/learner/quizzes/{id}/attempts (16/11, v3): đề riêng của em. answers = {vị trí: lựa chọn em thấy}; deadlineAt null = không giờ; serverNow để FE canh đồng hồ. */
public record AttemptResponse(java.util.UUID attemptId, int attemptNo, String mode, String kind, java.util.List<AttemptQuestionResponse> questions, java.util.Map<Integer, Integer> answers, java.time.Instant deadlineAt, java.time.Instant serverNow, java.time.Instant submittedAt) {
}
