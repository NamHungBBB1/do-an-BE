package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.EntitlementResponse;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/entitlements")
@RequiredArgsConstructor
public class EntitlementController {

    private final EntitlementService entitlementService;

    /** Gói của chính mình, cả gói hết hạn (lịch sử), hạn mới nhất trước. */
    @GetMapping
    public ApiResponse<java.util.List<EntitlementResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(entitlementService.listPlans(currentAccountId(jwt)));
    }

    @PostMapping("/grant")
    public void grantPlan() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    private static java.util.UUID currentAccountId(Jwt jwt) {
        return java.util.UUID.fromString(jwt.getSubject());
    }

}
