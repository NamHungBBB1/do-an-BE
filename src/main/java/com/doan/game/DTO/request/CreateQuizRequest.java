package com.doan.game.DTO.request;

/** Giáo viên tự chọn câu từ kho chung, phát cho một lớp (groupId phải là nhóm CLASS), có hạn nộp. 16/11 (v3): mode PRACTICE | TEST bắt buộc; TEST: drawCount (NULL = hết), durationMinutes (NULL = 1 phút × số câu mỗi em); newQuestions = câu mới lưu thành câu của giáo viên trong cùng giao dịch (flow import). */
public record CreateQuizRequest(java.util.UUID groupId, String title, java.util.List<java.util.UUID> questionIds, java.time.Instant dueAt, String mode, Integer drawCount, Integer durationMinutes, java.util.List<NewQuestionDto> newQuestions) {
}
