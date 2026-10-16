package com.doan.game.DTO.request;

/** Sửa đề CHƯA publish (quiz.md v2, 16/10); đề đã publish hoặc đề hậu trắc → 8002. */
public record UpdateQuizRequest(String title, java.util.List<java.util.UUID> questionIds, java.time.Instant dueAt) {
}
