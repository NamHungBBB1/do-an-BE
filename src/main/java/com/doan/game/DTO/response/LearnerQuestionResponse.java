package com.doan.game.DTO.response;

/** Câu hỏi cho TRẺ làm bài: không có correctIndex, không có lời giải. */
public record LearnerQuestionResponse(java.util.UUID id, String stem, java.util.List<String> options) {
}
