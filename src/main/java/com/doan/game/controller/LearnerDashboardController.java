package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.LearnerDashboardResponse;
import com.doan.game.service.LearnerDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * Cổng /api/learner/** đứng trước anyRequest, yêu cầu SCOPE_CHILD — token người lớn 403 (3004),
 * trẻ không tham số: slot lấy từ sub của token SLOT, chỉ thấy dữ liệu của chính mình.
 */
@RestController
@RequestMapping("/api/learner")
@RequiredArgsConstructor
public class LearnerDashboardController {

    private final LearnerDashboardService learnerDashboardService;

    /** Dashboard người học (token SLOT từ POST /api/slots/login) — contract Hưng duyệt 08/10. */
    @GetMapping("/dashboard")
    public ApiResponse<LearnerDashboardResponse> dashboard(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(learnerDashboardService.getDashboard(currentSlotId(jwt)));
    }

    /** Token SLOT: subject là id slot, KHÔNG phải id tài khoản (trẻ không có tài khoản). */
    private static UUID currentSlotId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

}
