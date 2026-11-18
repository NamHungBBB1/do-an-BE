package com.doan.game.DTO.response;

/**
 * BR-53 (Hưng 19/11): chương kế tiếp đang khoá vì em chưa nộp hậu trắc chương {@code chapter}; FE mở đề {@code quizId}.
 * Nằm trong GET /api/play/state và progress của dashboard; null = không khoá.
 */
public record PostTestRequiredResponse(int chapter, java.util.UUID quizId) {
}
