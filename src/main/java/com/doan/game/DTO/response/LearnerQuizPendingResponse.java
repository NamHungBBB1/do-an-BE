package com.doan.game.DTO.response;

/** Đề em chưa nộp. overdue = quá dueAt (FE hiện xám); hậu trắc không có dueAt nên overdue luôn false. 16/11 (v3): mode khác null → làm qua POST /api/learner/quizzes/{id}/attempts; questionCount = số câu mỗi em làm. */
public record LearnerQuizPendingResponse(java.util.UUID id, String kind, Integer chapter, String title, int questionCount, java.time.Instant dueAt, boolean overdue, String mode, Integer durationSeconds) {
}
