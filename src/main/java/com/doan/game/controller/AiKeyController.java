package com.doan.game.controller;

import com.doan.game.DTO.request.AiKeyRequest;
import com.doan.game.DTO.response.AiKeyResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.service.AiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Khoá AI trên trang admin (24/10): xem, nhập, thử, dừng / bật, xoá. Cổng /api/admin/** chỉ SCOPE_ADMIN. */
@RestController
@RequestMapping("/api/admin/ai-keys")
@RequiredArgsConstructor
public class AiKeyController {

    private final AiKeyService aiKeyService;

    @GetMapping
    public ApiResponse<List<AiKeyResponse>> list() {
        return ApiResponse.ok(aiKeyService.list());
    }

    @PostMapping
    public ApiResponse<AiKeyResponse> add(@AuthenticationPrincipal Jwt jwt, @RequestBody AiKeyRequest req) {
        return ApiResponse.ok(aiKeyService.add(currentAccountId(jwt), req));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<AiKeyResponse> test(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(aiKeyService.test(currentAccountId(jwt), id));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<AiKeyResponse> disable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(aiKeyService.setEnabled(currentAccountId(jwt), id, false));
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<AiKeyResponse> enable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(aiKeyService.setEnabled(currentAccountId(jwt), id, true));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        aiKeyService.delete(currentAccountId(jwt), id);
        return ApiResponse.ok();
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
