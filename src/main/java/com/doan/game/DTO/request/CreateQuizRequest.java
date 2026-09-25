package com.doan.game.DTO.request;

/** Giáo viên chọn câu từ kho dùng chung. */
public record CreateQuizRequest(String title, java.util.List<java.util.UUID> questionIds) {
}
