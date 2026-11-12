package com.doan.game.controller;

import com.doan.game.DTO.request.GrantRoleRequest;
import com.doan.game.DTO.request.SetPlanPriceRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.AdminAccountResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.PageResponse;
import com.doan.game.DTO.response.PaymentStatusResponse;
import com.doan.game.DTO.response.PlanResponse;
import com.doan.game.DTO.response.RoleGrantLogResponse;
import com.doan.game.DTO.response.RoleListResponse;
import com.doan.game.DTO.response.TransactionAdminResponse;
import com.doan.game.DTO.response.TransactionDetailResponse;
import com.doan.game.DTO.response.TransactionStatsResponse;
import com.doan.game.enums.TransactionStatus;
import com.doan.game.service.AdminService;
import com.doan.game.service.PaymentService;
import com.doan.game.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Luồng admin. Cả /api/admin/** đã khoá SCOPE_ADMIN trong SecurityConfig, nên ở đây không kiểm lại.
 * Có ruột (02/10) phần giá gói và giao dịch; vai nội bộ + tra tài khoản (AdminService) từ 15/10.
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
    public ApiResponse<PlanResponse> setPrice(@AuthenticationPrincipal Jwt jwt, @PathVariable String kind,
                                            @RequestBody SetPlanPriceRequest req) {
        return ApiResponse.ok(planService.setPrice(UUID.fromString(jwt.getSubject()), kind, req));
    }

    // ---- giao dịch ----
    /** Mới nhất trước; lọc theo trạng thái, từ khoá (orderCode hoặc email) và khoảng ngày. Trả kèm tổng số trang. */
    @GetMapping("/transactions")
    public ApiResponse<PageResponse<TransactionAdminResponse>> listTransactions(
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(paymentService.listTransactions(status, q, from, to, page, size));
    }

    /** Thống kê theo trạng thái và doanh thu từng ngày trong khoảng ngày (giờ Việt Nam). */
    @GetMapping("/transactions/stats")
    public ApiResponse<TransactionStatsResponse> transactionStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(paymentService.stats(from, to));
    }

    /** Chi tiết một giao dịch theo orderCode; không có → 4005. */
    @GetMapping("/transactions/{orderCode:\\d+}")
    public ApiResponse<TransactionDetailResponse> getTransaction(@PathVariable long orderCode) {
        return ApiResponse.ok(paymentService.getTransaction(orderCode));
    }

    /** Hỏi lại PayOS ngay cho một giao dịch còn PENDING (khi nghi webhook không tới). */
    @PostMapping("/transactions/{orderCode}/reconcile")
    public ApiResponse<PaymentStatusResponse> reconcile(@PathVariable long orderCode) {
        return ApiResponse.ok(paymentService.reconcile(orderCode));
    }

    // ---- vai nội bộ (PR A, 15/10) ----
    /** Cấp ADMIN / GAME_EDITOR / GAME_REVIEWER / GAME_MANAGER, bắt buộc có reason; đã có thì lặp vô hại. */
    @PostMapping("/roles/grant")
    public ApiResponse<RoleListResponse> grantRole(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestBody GrantRoleRequest req) {
        return ApiResponse.ok(adminService.grantRole(UUID.fromString(jwt.getSubject()), req));
    }

    /** Thu một vai; không giữ vai đó → 1003; không tự thu ADMIN của chính mình → 1001. */
    @PostMapping("/roles/revoke")
    public ApiResponse<RoleListResponse> revokeRole(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestBody GrantRoleRequest req) {
        return ApiResponse.ok(adminService.revokeRole(UUID.fromString(jwt.getSubject()), req));
    }

    /** Ai đang giữ vai nội bộ: mỗi tài khoản một dòng kèm đủ vai và gói; danh sách nhỏ nên không phân trang. */
    @GetMapping("/roles")
    public ApiResponse<List<AccountResponse>> listInternalAccounts() {
        return ApiResponse.ok(adminService.listInternalAccounts());
    }

    /** Sổ cấp / thu vai: mới nhất trước, lọc theo subjectId nếu có; trả kèm tổng số dòng / số trang. */
    @GetMapping("/roles/log")
    public ApiResponse<PageResponse<RoleGrantLogResponse>> listRoleLog(@RequestParam(required = false) UUID subjectId,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(adminService.listRoleLog(subjectId, page, size));
    }

    // ---- danh sách mọi tài khoản (Hưng 25/10, trang phân quyền) ----
    /** Xếp theo email; q lọc email / tên, role lọc vai nội bộ, plan lọc gói còn hạn. Trả kèm tổng số. */
    @GetMapping("/accounts/list")
    public ApiResponse<PageResponse<AdminAccountResponse>> listAccounts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) com.doan.game.enums.Role role,
            @RequestParam(required = false) com.doan.game.enums.PlanKind plan,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminService.listAccounts(q, role, plan, page, size));
    }

    // ---- tra tài khoản (tối 15/10) ----
    /** Tìm đúng một tài khoản theo email để lấy id cho màn cấp vai; cùng dạng với GET /api/auth/me. */
    @GetMapping("/accounts")
    public ApiResponse<AccountResponse> findAccount(@RequestParam(required = false) String email) {
        return ApiResponse.ok(adminService.findAccount(email));
    }
}
