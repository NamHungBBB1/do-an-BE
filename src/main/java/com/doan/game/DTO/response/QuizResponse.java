package com.doan.game.DTO.response;

/** Một đề của một lớp. kind CLASS_TEST (giáo viên ra, null trong DB coi là CLASS_TEST) hoặc POST_TEST (hậu trắc chương `chapter`, BE tự tạo, dueAt null). publishedAt null = nháp. */
public record QuizResponse(java.util.UUID id, java.util.UUID groupId, String kind, Integer chapter, String title, int questionCount, java.time.Instant dueAt, java.time.Instant publishedAt, int submittedCount) {
}
