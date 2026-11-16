package com.doan.game.DTO.response;

/** Đề em đã nộp, kèm điểm. 16/11 (v3): score null với đề Kiểm tra trước hạn nộp (chỉ báo đã nộp); mode, attemptId như pending (mở lại xem đúng / sai sau hạn nộp). */
public record LearnerQuizCompletedResponse(java.util.UUID id, String kind, Integer chapter, String title, Integer score, int totalQuestions, java.time.Instant submittedAt, String mode, java.util.UUID attemptId) {
}
