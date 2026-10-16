package com.doan.game.DTO.response;

/** Một dòng kết quả lớp: mọi slot ACTIVE + slot không còn ACTIVE nhưng đã nộp (WIPED → code, displayName null). attempted = false là chưa nộp (score, submittedAt null). */
public record QuizResultResponse(java.util.UUID slotId, String code, String displayName, String status, boolean attempted, Integer score, int totalQuestions, java.time.Instant submittedAt) {
}
