package com.doan.game.DTO.response;

/** Một dòng kết quả lớp: mọi slot ACTIVE + slot không còn ACTIVE nhưng đã nộp (WIPED → code, displayName null). attempted = false là chưa nộp (score, submittedAt null). 16/11 (v3), từ lượt làm đầu: attemptCode (6 ký tự đầu id lượt), startedAt, durationSeconds (làm trong), leaveCount, autoSubmitted; state NOT_OPENED | IN_PROGRESS | SUBMITTED. */
public record QuizResultResponse(java.util.UUID slotId, String code, String displayName, String status, boolean attempted, Integer score, int totalQuestions, java.time.Instant submittedAt, String attemptCode, java.time.Instant startedAt, Integer durationSeconds, Integer leaveCount, Boolean autoSubmitted, String state) {
}
