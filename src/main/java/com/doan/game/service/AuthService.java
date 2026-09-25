package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Đăng ký, xác minh email, đăng nhập, gộp cách đăng nhập.
 *
 * Bảng phụ trách: Account, Credential
 */
public interface AuthService {

    AccountResponse dangKy(RegisterRequest req);

    void xacMinhEmail(String token);

    TokenResponse dangNhap(LoginRequest req);

    TokenResponse dangNhapGoogle(String idToken);

    void lienKetCachDangNhap(UUID accountId, LinkCredentialRequest req);

}
