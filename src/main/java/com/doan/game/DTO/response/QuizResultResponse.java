package com.doan.game.DTO.response;

/** Kết quả một em, gắn vào slot nên không lọt sang phạm vi khác. */
public record QuizResultResponse(java.util.UUID slotId, int score, int tongCau) {
}
