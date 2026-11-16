package com.doan.game.DTO.response;

/** Đúng / sai một vị trí của lượt làm cho TRẺ (16/11, v3). Không correctIndex. */
public record AttemptAnswerReviewResponse(int pos, Integer chosen, boolean correct) {
}
