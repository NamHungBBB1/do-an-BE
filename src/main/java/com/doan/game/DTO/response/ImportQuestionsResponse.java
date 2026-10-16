package com.doan.game.DTO.response;

/** Kết quả nhập kho: skipped = câu thiếu options / không hợp lệ bị bỏ qua. */
public record ImportQuestionsResponse(int inserted, int updated, int skipped) {
}
