package com.doan.game.DTO.request;

/** Token từ email, dùng một lần, chỉ lưu hash (VerificationToken). */
public record ResetPasswordRequest(String token, String newPassword) {
}
