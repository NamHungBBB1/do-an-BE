package com.doan.game.DTO.request;

/** Giáo viên tự chọn câu từ kho chung, phát cho một lớp (groupId phải là nhóm CLASS), có hạn nộp. */
public record CreateQuizRequest(java.util.UUID groupId, String title, java.util.List<java.util.UUID> questionIds, java.time.Instant dueAt) {
}
