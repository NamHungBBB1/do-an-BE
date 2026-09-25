package com.doan.game.controller;

import com.doan.game.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public void dangKy() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/verify")
    public void xacMinhEmail() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/login")
    public void dangNhap() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/login/google")
    public void dangNhapGoogle() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
