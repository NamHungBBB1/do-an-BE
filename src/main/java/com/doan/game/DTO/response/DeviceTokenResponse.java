package com.doan.game.DTO.response;

/** Kết quả đăng ký token thiết bị. Luôn true khi request hợp lệ — gọi lại cùng token vẫn 200. */
public record DeviceTokenResponse(boolean registered) {
}
