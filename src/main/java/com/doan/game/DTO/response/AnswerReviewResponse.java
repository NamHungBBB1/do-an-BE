package com.doan.game.DTO.response;

/** Đúng / sai từng câu trả cho TRẺ ngay sau khi nộp. KHÔNG có correctIndex, không lời giải (đợt 1). */
public record AnswerReviewResponse(java.util.UUID questionId, Integer chosenIndex, boolean correct) {
}
