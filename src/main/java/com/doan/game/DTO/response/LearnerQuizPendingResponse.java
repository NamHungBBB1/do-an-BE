package com.doan.game.DTO.response;

/** Đề em chưa nộp. overdue = quá dueAt (FE hiện xám); hậu trắc không có dueAt nên overdue luôn false. */
public record LearnerQuizPendingResponse(java.util.UUID id, String kind, Integer chapter, String title, int questionCount, java.time.Instant dueAt, boolean overdue) {
}
