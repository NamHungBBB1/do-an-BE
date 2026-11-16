package com.doan.game.DTO.response;

/** Xem lại / nộp lượt làm (16/11, v3). Đề Kiểm tra trước hạn nộp và lượt chưa nộp: score, review null. */
public record AttemptReviewResponse(java.util.UUID attemptId, int attemptNo, String mode, String kind, java.time.Instant submittedAt, boolean autoSubmitted, int answered, int total, Integer score, java.util.List<AttemptAnswerReviewResponse> review) {
}
