package com.doan.game.controller;

import com.doan.game.DTO.request.SetPlanPriceRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.PaymentStatusResponse;
import com.doan.game.DTO.response.PlanResponse;
import com.doan.game.DTO.response.TransactionAdminResponse;
import com.doan.game.enums.TransactionStatus;
import com.doan.game.service.AdminService;
import com.doan.game.service.PaymentService;
import com.doan.game.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Luồng admin. Cả /api/admin/** đã khoá SCOPE_ADMIN trong SecurityConfig, nên ở đây không kiểm lại.
 * Có ruột (02/10) phần giá gói và giao dịch; phần vai (AdminService) còn là khung.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final PlanService planService;
    private final PaymentService paymentService;

    // ---- giá gói ----
    @PutMapping("/plans/{kind}")
    public ApiResponse<PlanResponse> datGia(@AuthenticationPrincipal Jwt jwt, @PathVariable String kind,
                                            @RequestBody SetPlanPriceRequest req) {
        return ApiResponse.ok(planService.datGia(UUID.fromString(jwt.getSubject()), kind, req));
    }

    // ---- giao dịch ----
    /** Mới nhất trước. Không trả tổng số dòng: FE lật trang tới khi nhận danh sách rỗng. */
    @GetMapping("/transactions")
    public ApiResponse<List<TransactionAdminResponse>> danhSachGiaoDich(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(paymentService.danhSachGiaoDich(status, page, size));
    }

    /** Hỏi lại PayOS ngay cho một giao dịch còn PENDING (khi nghi webhook không tới). */
    @PostMapping("/transactions/{orderCode}/reconcile")
    public ApiResponse<PaymentStatusResponse> doiSoat(@PathVariable long orderCode) {
        return ApiResponse.ok(paymentService.doiSoat(orderCode));
    }

    // ---- vai nội bộ: khung, chưa cài đặt ----
    @PostMapping("/roles/grant")
    public void phatVai() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/roles/revoke")
    public void thuVai() {
        throw new UnsupportedOperationException("chua cai dat");
    }
}
