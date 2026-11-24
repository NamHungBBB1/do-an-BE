package com.doan.game.DTO.request;

/**
 * Sửa hồ sơ của CHÍNH tài khoản người lớn đang đăng nhập (24/11). Chỉ có tên hiển thị:
 * email là danh tính đăng nhập (đổi được thì thành chuyện xác minh lại), gói/vai lấy từ
 * database nên không bao giờ nhận từ client. Trường khác gửi lên bị bỏ qua, không báo lỗi.
 */
public record UpdateProfileRequest(String displayName) {
}
