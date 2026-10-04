package com.doan.game.controller;

import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/entitlements")
@RequiredArgsConstructor
public class EntitlementController {

    private final EntitlementService entitlementService;

    @GetMapping
    public void listPlans() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/grant")
    public void grantPlan() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
