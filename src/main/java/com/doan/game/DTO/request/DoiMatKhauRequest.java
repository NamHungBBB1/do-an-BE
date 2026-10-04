package com.doan.game.DTO.request;

/**
 * Đổi mật khẩu khi ĐANG đăng nhập: phải đưa mật khẩu cũ, nếu không thì token đánh cắp cũng
 * đổi được mật khẩu và khoá chủ thật ra ngoài.
 */
public record DoiMatKhauRequest(String oldPassword, String newPassword) {
}
