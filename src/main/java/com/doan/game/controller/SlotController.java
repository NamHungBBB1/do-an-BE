package com.doan.game.controller;

import com.doan.game.DTO.request.ChangePinRequest;
import com.doan.game.DTO.request.CreateSlotRequest;
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

    @PostMapping("/bulk")
    public void openSlots() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/return")
    public void returnSlot() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @DeleteMapping("/{id}")
    public void wipeSlot() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/pin")
    public void changePin() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> loginSlot(@Valid @RequestBody SlotLoginRequest req) {
        return ApiResponse.ok(slotService.loginSlot(req.code(), req.pin()));
    }

}
