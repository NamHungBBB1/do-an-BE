package com.doan.game.controller;

import com.doan.game.DTO.request.ChangePasswordRequest;
import com.doan.game.DTO.request.ForgotPasswordRequest;
import com.doan.game.DTO.request.LinkCredentialRequest;
import com.doan.game.DTO.request.LoginRequest;
import com.doan.game.DTO.request.RegisterRequest;
import com.doan.game.DTO.request.ResetPasswordRequest;
import com.doan.game.DTO.request.VerifyEmailRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cửa vào HTTP cho auth. Tầng này MỎNG: nhận, gọi service, trả về. Có ruột (02/10), bộ sinh
 * khung giữ nguyên tệp này.
 *
 * KHÔNG try/catch ở đây: lỗi ném AppException(ErrorCode) và GlobalExceptionHandler dựng vỏ
 * ApiResponse cho tất cả. Chỗ DUY NHẤT được phép bắt lỗi là endpoint mà bên ngoài đòi phải
 * trả 200 — webhook PayOS, không phải file này.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<AccountResponse> register(@RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req));
    }

    /**
     * Nhập mã OTP 6 số trong mail đăng ký (chốt 14/09). Đúng thì trả token luôn — không bắt đăng
     * nhập lại. permitAll: người dùng chưa có token nào.
     */
    @PostMapping("/verify")
    public ApiResponse<TokenResponse> verifyEmail(@RequestBody VerifyEmailRequest req) {
        return ApiResponse.ok(authService.verifyEmail(req));
    }

    /** Gửi lại mã xác minh. Email không có tài khoản cũng trả 200 — không lộ email nào đã đăng ký. */
    @PostMapping("/verify/resend")
    public ApiResponse<Void> resendVerification(@RequestParam String email) {
        authService.resendVerification(email);
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    /**
     * Tôi là ai. Cần token (không nằm trong permitAll) và CỐ Ý trả vai/gói lấy từ database,
     * không giải mã JWT — token cũ có thể đã bị thu hồi hoặc vai đã đổi từ lúc phát.
     */
    @GetMapping("/me")
    public ApiResponse<AccountResponse> getMe(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(authService.getMe(currentAccountId(jwt)));
    }

    /**
     * Đổi mật khẩu khi đang đăng nhập. Trả token mới vì đổi mật khẩu làm token cũ hết hiệu lực
     * (Account.tokenVersion) — không trả thì người dùng tự đá mình ra ngoài ngay khi bấm Lưu.
     */
    @PostMapping("/password/change")
    public ApiResponse<TokenResponse> changePassword(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestBody ChangePasswordRequest req) {
        return ApiResponse.ok(authService.changePassword(currentAccountId(jwt), req));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * Nút "Đăng nhập bằng Google": FE lấy ID token của Firebase rồi gửi thẳng vào đây.
     * permitAll — không có JWT của FinTeen trong tay người dùng mới (đang định đăng nhập).
     */
    @PostMapping("/login/google")
    public ApiResponse<TokenResponse> loginWithGoogle(@RequestBody LinkCredentialRequest req) {
        return ApiResponse.ok(authService.loginWithGoogle(req));
    }

    /**
     * Gắn Google vào tài khoản đang đăng nhập. KHÔNG nằm trong permitAll: chỉ người cầm JWT
     * FinTeen mới được thêm cách đăng nhập — nếu cho ẩn danh thì ai lấy được idToken Google của
     * người khác cũng gắn vào tài khoản mình.
     */
    @PostMapping("/link/google")
    public ApiResponse<Void> linkGoogle(@AuthenticationPrincipal Jwt jwt,
                                           @RequestBody LinkCredentialRequest req) {
        authService.linkCredential(currentAccountId(jwt), req);
        return ApiResponse.ok();
    }

    /** Luôn 200, kể cả khi email không tồn tại — xem chú thích ở AuthServiceImpl.forgotPassword. */
    @PostMapping("/password/forgot")
    public ApiResponse<Void> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req);
        return ApiResponse.ok();
    }

    /** Đặt lại mật khẩu bằng email + mã OTP trong mail + mật khẩu mới. Mọi token cũ chết. */
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
        return ApiResponse.ok();
    }
}
