package com.doan.game.DTO.response;

/** HS512. Scope trong token chỉ là gợi ý cho FE; BE kiểm lại gói ở mọi thao tác. */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
}
