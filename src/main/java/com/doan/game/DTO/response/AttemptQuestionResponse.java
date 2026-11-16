package com.doan.game.DTO.response;

/** Một câu của lượt làm cho TRẺ (16/11, v3): options đã xáo; không id câu, không correctIndex. */
public record AttemptQuestionResponse(int pos, String stem, java.util.List<String> options) {
}
