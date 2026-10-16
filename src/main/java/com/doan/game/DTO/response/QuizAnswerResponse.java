package com.doan.game.DTO.response;

/** Từng câu của một em cho GIÁO VIÊN xem (có correctIndex). chosenIndex null = bỏ trống. */
public record QuizAnswerResponse(java.util.UUID questionId, String stem, Integer chosenIndex, int correctIndex, boolean correct) {
}
