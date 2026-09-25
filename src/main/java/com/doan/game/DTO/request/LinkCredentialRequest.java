package com.doan.game.DTO.request;

/** Thêm một cách đăng nhập vào tài khoản đã có. Chỉ gộp khi nhà cung cấp xác nhận email đã xác minh VÀ người dùng chứng minh được quyền sở hữu tài khoản cũ. */
public record LinkCredentialRequest(String provider, String idToken) {
}
