package com.doan.game.DTO.request;

/** Đặt lại mật khẩu bằng mã OTP 6 số gửi qua email (chỉ lưu hash BCrypt ở VerificationToken). */
public record ResetPasswordRequest(String email, String otp, String newPassword) {
}
