package com.doan.game.DTO.response;

/** Như TokenResponse, thêm hồ sơ slot để FE hiện tên trẻ ngay sau khi đăng nhập. */
public record SlotLoginResponse(String accessToken, String refreshToken, long expiresIn, SlotProfileResponse slot) {
}
