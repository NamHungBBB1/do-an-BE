package com.doan.game.controller;

import com.doan.game.DTO.request.RegisterDeviceTokenRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.DeviceTokenRemovedResponse;
import com.doan.game.DTO.response.DeviceTokenResponse;
import com.doan.game.service.DeviceTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Đăng ký / xoá token FCM của app mobile (26/11).
 *
 * <p>Danh tính lấy từ token, không có accountId do client truyền lên: không đăng ký hộ máy cho
 * người khác và không xoá token của người khác được.
 */
@RestController
@RequestMapping("/api/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    /** App gọi mỗi lần mở và mỗi lần token đổi; cùng token gọi lại vẫn 200. */
    @PostMapping
    public ApiResponse<DeviceTokenResponse> register(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody RegisterDeviceTokenRequest req) {
        deviceTokenService.register(currentAccountId(jwt), req.token(), req.platform());
        return ApiResponse.ok(new DeviceTokenResponse(true));
    }

    /** Đăng xuất trên máy đó. Token của tài khoản khác thì không xoá được (removed = false). */
    @DeleteMapping("/{token}")
    public ApiResponse<DeviceTokenRemovedResponse> unregister(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable String token) {
        boolean removed = deviceTokenService.unregister(currentAccountId(jwt), token);
        return ApiResponse.ok(new DeviceTokenRemovedResponse(removed));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
