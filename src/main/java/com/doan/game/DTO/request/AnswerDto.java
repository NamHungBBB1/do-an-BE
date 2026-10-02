package com.doan.game.DTO.request;

/** Một câu trả lời quiz. */
public record AnswerDto(java.util.UUID questionId, int chosenIndex) {
}
