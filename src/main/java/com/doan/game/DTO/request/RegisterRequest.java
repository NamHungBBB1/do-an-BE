package com.doan.game.DTO.request;

/** Người lớn đăng ký. Trẻ không bao giờ đi qua đây. */
public record RegisterRequest(String email, String phone, String password, String displayName) {
}
