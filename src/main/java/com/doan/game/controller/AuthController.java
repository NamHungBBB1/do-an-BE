package com.doan.game.controller;

import com.doan.game.DTO.request.ChangePasswordRequest;
import com.doan.game.DTO.request.ForgotPasswordRequest;
import com.doan.game.DTO.request.LinkCredentialRequest;
import com.doan.game.DTO.request.LoginRequest;
import com.doan.game.DTO.request.RegisterRequest;
import com.doan.game.DTO.request.ResetPasswordRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.service.AuthService;
import com.doan.game.web.HtmlPages;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
 * KHÔNG try/catch ở đây (kể cả các trang HTML): lỗi ném AppException(ErrorCode) và GlobalExceptionHandler dựng vỏ
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
     * Link người dùng bấm từ hộp thư, không có token ở đó nên đường này permitAll.
     * Trả HTML chứ không trả JSON: đây là trình duyệt đang mở, không phải FE gọi bằng fetch.
     * Lỗi nghiệp vụ cũng phải ra HTML — ném AppException thì GlobalExceptionHandler dựng JSON,
     * người dùng mở link hỏng và thấy nguyên khối {"code":3012,...} trên trang trắng.
     */
    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String verifyEmail(@RequestParam String token) {
        // Lỗi nghiệp vụ không bắt ở đây: GlobalExceptionHandler thấy endpoint trả HTML thì tự dựng
        // trang lỗi HTML và GIỮ NGUYÊN mã lỗi (400 link sai, 410 hết hạn).
        authService.verifyEmail(token);
        return HtmlPages.page("Đã xác minh email", "Xong rồi. Quay lại ứng dụng và đăng nhập bằng mật khẩu của bạn.");
    }

    /** Gửi lại mail xác minh. Email không có tài khoản cũng trả 200 — không lộ email nào đã đăng ký. */
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

    /**
     * Trang nhận mật khẩu mới từ link trong mail. Link trong mail là GET, còn đổi mật khẩu thì phải
     * POST — không có trang này thì người dùng bấm link là gặp 405, đúng là loại lỗi mà FE chưa có
     * domain cứu nổi. BE tự phục vụ form giống /verify: không đoán đường dẫn trang của FE.
     *
     * CỐ Ý không kiểm token ở đây. Trình đọc mail của nhiều hãng tự mở sẵn link để quét virus, mở
     * một lần là token hỏng và người dùng không làm gì cũng thấy "link không đúng". Chỉ POST mới
     * thực sự tiêu token.
     *
     * action CỐ Ý là đường dẫn TƯƠNG ĐỐI ("reset"). Ghi chết "/api/auth/password/reset" thì khi
     * VPS chạy với CONTEXT_PATH=/finteen, form sẽ POST sang .../api/auth/password/reset không có
     * /finteen và nhận 404 — người dùng không đổi được mật khẩu trên máy chủ.
     */
    @GetMapping(value = "/password/reset", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String resetPasswordPage(@RequestParam String token) {
        return HtmlPages.resetPasswordForm(token);
    }

    @PostMapping(value = "/password/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> resetPassword(@RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
        return ApiResponse.ok();
    }

    /**
     * Biến thể form-urlencoded cho trang ở trên: trình duyệt gửi form, không có ứng dụng JS nào để
     * dựng JSON. Trả HTML để người dùng thấy kết quả ngay tại chỗ, không bị ném về JSON lạnh lẽo.
     * Lỗi NGHIỆP VỤ trong form này cũng bắt và trả HTML (xem nhánh try bên dưới).
     *
     * produces BẮT BUỘC: không có thì Spring gán Content-Type mặc định text/plain và trình duyệt
     * hiện nguyên chữ "<h1 ...>Đã đổi mật khẩu</h1>" thay vì render trang.
     */
    @PostMapping(value = "/password/reset",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String resetPasswordForm(@RequestParam String token, @RequestParam String newPassword) {
        authService.resetPassword(new ResetPasswordRequest(token, newPassword));
        return HtmlPages.page("Đã đổi mật khẩu", "Đăng nhập bằng mật khẩu vừa đặt.");
    }
}
