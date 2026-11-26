package com.doan.game.DTO.response;

/**
 * Kết quả xoá token thiết bị. {@code false} khi token không có hoặc thuộc tài khoản khác — vẫn 200
 * để client gọi lặp lúc đăng xuất không phải xử lý lỗi.
 */
public record DeviceTokenRemovedResponse(boolean removed) {
}
