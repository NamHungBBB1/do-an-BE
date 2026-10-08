package com.doan.game.controller;

import com.doan.game.DTO.request.ChangePinRequest;
import com.doan.game.DTO.request.CreateSlotRequest;
import com.doan.game.DTO.request.CreateSlotsRequest;
import com.doan.game.DTO.request.SlotLoginRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.SlotResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.service.SlotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * /login là cổng duy nhất permitAll trong /api/slots (xem SecurityConfig) vì trẻ chưa có token
 * khi mới bấm mã trên giấy.
 */
@RestController
@RequestMapping("/api/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;

    /**
     * Mở một slot cho trẻ. groupId nằm trong body (CreateSlotRequest, đổi qua PR #10 Hưng duyệt);
     * service tự kiểm nhóm có phải của người gọi — không tin vai trong JWT.
     */
    @PostMapping
    public ApiResponse<SlotResponse> openSlot(@AuthenticationPrincipal Jwt jwt,
                                              @RequestBody CreateSlotRequest req) {
        return ApiResponse.ok(slotService.openSlot(UUID.fromString(jwt.getSubject()), req));
    }

    /**
     * Mở nhiều slot một lần (giáo viên dán danh sách lớp). Cùng groupId, vượt hạn mức từ chối
     * CẢ LÔ (3006), một transaction. groupId lấy từ body, callerId từ JWT sub — như openSlot.
     */
    @PostMapping("/bulk")
    public ApiResponse<java.util.List<SlotResponse>> openSlots(@AuthenticationPrincipal Jwt jwt,
                                                               @RequestBody CreateSlotsRequest req) {
        return ApiResponse.ok(slotService.openSlots(UUID.fromString(jwt.getSubject()), req));
    }

    /**
     * Trả slot: ACTIVE → ARCHIVED, giữ tên và mã (mã hết hiệu lực — trẻ login lại được 3009),
     * trả lại chỗ cho hạn mức. Chỉ chủ nhóm, slot còn ACTIVE.
     */
    @PostMapping("/{id}/return")
    public ApiResponse<Void> returnSlot(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        slotService.returnSlot(UUID.fromString(jwt.getSubject()), id);
        return ApiResponse.ok();
    }

    /** Xoá sạch: WIPED — bỏ tên, mã, PIN, giữ dòng cho báo cáo cũ. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> wipeSlot(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        slotService.wipeSlot(UUID.fromString(jwt.getSubject()), id);
        return ApiResponse.ok();
    }

    /** Đổi PIN: vẫn ACTIVE, reset bộ đếm sai và thời khoá. */
    @PostMapping("/{id}/pin")
    public ApiResponse<Void> changePin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @RequestBody ChangePinRequest req) {
        slotService.changePin(UUID.fromString(jwt.getSubject()), id, req);
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> loginSlot(@Valid @RequestBody SlotLoginRequest req) {
        return ApiResponse.ok(slotService.loginSlot(req.code(), req.pin()));
    }

}
