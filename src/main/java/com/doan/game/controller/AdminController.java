package com.doan.game.controller;

import com.doan.game.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping("/roles/grant")
    public void phatVai() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/roles/revoke")
    public void thuVai() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
