package com.doan.game.DTO.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Đăng ký token FCM của một máy (bảng device_token, 26/11).
 *
 * <p>{@code platform} bỏ trống thì hiểu là ANDROID — app demo hiện chỉ có Android; client mới nên
 * gửi rõ. BE lưu chuỗi chứ không ENUM để sau này thêm IOS không phải sửa ràng buộc DB.
 */
public record RegisterDeviceTokenRequest(
        @NotBlank(message = "token không được để trống")
        @Size(max = 512, message = "token tối đa 512 ký tự")
        String token,
        String platform) {
}
