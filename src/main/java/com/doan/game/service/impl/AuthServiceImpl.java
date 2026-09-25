package com.doan.game.service.impl;

import com.doan.game.service.AuthService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Đăng ký, xác minh email, đăng nhập, gộp cách đăng nhập.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    @Override
    public AccountResponse dangKy(RegisterRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void xacMinhEmail(String token) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public TokenResponse dangNhap(LoginRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public TokenResponse dangNhapGoogle(String idToken) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void lienKetCachDangNhap(UUID accountId, LinkCredentialRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
