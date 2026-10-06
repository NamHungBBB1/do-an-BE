package com.doan.game.DTO.request;

/** Xin mã OTP đặt lại mật khẩu. Email không tồn tại vẫn trả 200, không để lộ ai có tài khoản. */
public record ForgotPasswordRequest(String email) {
}
