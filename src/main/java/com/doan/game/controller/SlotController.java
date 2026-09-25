package com.doan.game.auth.web;

import com.doan.game.auth.AuthService;
import com.doan.game.auth.web.AuthDtos.*;
import com.doan.game.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Người lớn phát và thu hồi chỗ ngồi của trẻ. Đây vẫn là auth — slot CHÍNH LÀ thông tin
 * đăng nhập của trẻ, không phải hồ sơ học tập.
 *
 * Phụ huynh và giáo viên dùng chung cửa này; khác nhau ở `context` và ở hạn mức (4 với 40).
 * Không tách thành hai controller vì mã sẽ giống nhau từng dòng, và một người có thể giữ cả hai vai.
 */
@RestController
@RequestMapping("/api/slots")
public class SlotController {

    private final AuthService auth;

    public SlotController(AuthService auth) {
        this.auth = auth;
    }

    /** PIN gốc chỉ trả về ĐÚNG LẦN NÀY. Mất thì reset, không có đường đọc lại. */
    @PostMapping
    public ApiResponse<NewSlotView> create(@AuthenticationPrincipal Jwt j,
                                           @RequestBody @Valid CreateSlotRequest r) {
        AuthService.Issued i = auth.createSlot(AuthController.adultId(j), r.displayName(), r.context());
        return ApiResponse.ok(new NewSlotView(i.slot().getId(), i.slot().getCode(), i.pin(),
                i.slot().getDisplayName(), i.slot().getContext()));
    }

    @GetMapping
    public ApiResponse<List<SlotView>> list(@AuthenticationPrincipal Jwt j) {
        return ApiResponse.ok(auth.slotsOf(AuthController.adultId(j)).stream().map(SlotView::of).toList());
    }

    @PostMapping("/{id}/reset-pin")
    public ApiResponse<Map<String, String>> resetPin(@AuthenticationPrincipal Jwt j,
                                                     @PathVariable UUID id) {
        return ApiResponse.ok(Map.of("pin", auth.resetPin(AuthController.adultId(j), id)));
    }

    /**
     * Nút KẾT THÚC LỚP HỌC. Lưu trữ toàn bộ slot của lớp hiện tại và trả 40 chỗ về để tái dùng.
     * Data cũ giữ nguyên — cô cần nó để so trước/sau. Không xoá gì cả.
     */
    @PostMapping("/end-class")
    public ApiResponse<Map<String, Integer>> endClass(@AuthenticationPrincipal Jwt j) {
        return ApiResponse.ok(Map.of("archived", auth.endClass(AuthController.adultId(j))));
    }
}
