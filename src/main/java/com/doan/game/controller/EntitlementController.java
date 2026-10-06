package com.doan.game.controller;

import com.doan.game.DTO.request.GrantPlanRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.EntitlementResponse;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * /grant khoá SCOPE_ADMIN trong SecurityConfig; GET còn lại rơi vào anyRequest → chỉ token
 * typ ACCOUNT (token trẻ bị 403).
 */
@RestController
@RequestMapping("/api/entitlements")
@RequiredArgsConstructor
public class EntitlementController {

    private final EntitlementService entitlementService;

    /** Gói của chính mình, cả gói hết hạn (lịch sử), hạn mới nhất trước. */
    @GetMapping
    public ApiResponse<List<EntitlementResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(entitlementService.listPlans(currentAccountId(jwt)));
    }

    /** Admin cấp gói không cần thanh toán. Nghiệp vụ nằm ở service. */
    @PostMapping("/grant")
    public ApiResponse<EntitlementResponse> grant(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestBody GrantPlanRequest req) {
        return ApiResponse.ok(entitlementService.grantPlan(currentAccountId(jwt), req));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

}
