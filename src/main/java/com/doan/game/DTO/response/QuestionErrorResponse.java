package com.doan.game.DTO.response;

/** Một lỗi của lô câu (16/11, v3), trả trong result của lỗi 1001 để FE tô đỏ đúng dòng. */
public record QuestionErrorResponse(int index, String field, String message) {
}
