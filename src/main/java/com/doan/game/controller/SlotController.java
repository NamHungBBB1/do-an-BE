package com.doan.game.controller;

import com.doan.game.DTO.request.ChangePinRequest;
import com.doan.game.DTO.request.SlotLoginRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.service.SlotService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping
    public void moSlot() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/bulk")
    public void moNhieuSlot() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/return")
    public void traSlot() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @DeleteMapping("/{id}")
    public void xoaSach() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/pin")
    public void doiPin() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> dangNhapTre(@RequestBody SlotLoginRequest req) {
        return ApiResponse.ok(slotService.dangNhapTre(req.code(), req.pin()));
    }

}
