package com.doan.game.DTO.response;

/** Thành tựu / chứng chỉ của một slot. */
public record AchievementResponse(String code, String certificateUrl, java.time.Instant earnedAt) {
}
