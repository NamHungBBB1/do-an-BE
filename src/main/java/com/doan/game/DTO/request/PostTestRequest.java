package com.doan.game.DTO.request;

/** Giáo viên bật / tắt hậu trắc cho một lớp (POST /api/groups/{id}/post-test, 16/10). */
public record PostTestRequest(boolean enabled) {
}
