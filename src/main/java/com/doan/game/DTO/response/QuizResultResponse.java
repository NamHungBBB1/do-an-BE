package com.doan.game.DTO.response;

/** Kết quả một em; attempted = false là chưa làm (không có dòng QuizResult). */
public record QuizResultResponse(java.util.UUID slotId, boolean attempted, int score, int totalQuestions) {
}
