package com.doan.game.DTO.response;

/** HS512, hạn 1 giờ; refresh 7 ngày. Vai nằm trong claim scope. */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
}
