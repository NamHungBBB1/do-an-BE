package com.doan.game.DTO.response;

/** Đề em đã nộp, kèm điểm. 16/11 (v3): score null với đề Kiểm tra trước hạn nộp (chỉ báo đã nộp); mode như pending. */
public record LearnerQuizCompletedResponse(java.util.UUID id, String kind, Integer chapter, String title, Integer score, int totalQuestions, java.time.Instant submittedAt, String mode) {
}
