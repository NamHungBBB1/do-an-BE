package com.doan.game.DTO.request;

/** Bài làm của một em; máy chủ chấm, đáp án đúng không xuống client. */
public record SubmitQuizRequest(java.util.List<AnswerDto> answers) {
}
