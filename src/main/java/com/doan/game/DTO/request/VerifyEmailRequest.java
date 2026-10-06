package com.doan.game.DTO.request;

/** Xác minh email bằng mã OTP 6 số trong mail đăng ký. */
public record VerifyEmailRequest(String email, String otp) {
}
