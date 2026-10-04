package com.doan.game.controller;

import com.doan.game.DTO.request.DoiMatKhauRequest;
import com.doan.game.DTO.request.ForgotPasswordRequest;
import com.doan.game.DTO.request.LinkCredentialRequest;
import com.doan.game.DTO.request.LoginRequest;
import com.doan.game.DTO.request.RegisterRequest;
import com.doan.game.DTO.request.ResetPasswordRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.exception.AppException;
import com.doan.game.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    public ApiResponse<AccountResponse> dangKy(@RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.dangKy(req));
    }

    /**
     * Link người dùng bấm từ hộp thư, không có token ở đó nên đường này permitAll.
     * Trả HTML chứ không trả JSON: đây là trình duyệt đang mở, không phải FE gọi bằng fetch.
     * Lỗi nghiệp vụ cũng phải ra HTML — ném AppException thì GlobalExceptionHandler dựng JSON,
     * người dùng mở link hỏng và thấy nguyên khối {"code":3012,...} trên trang trắng.
     */
    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> xacMinhEmail(@RequestParam String token) {
        try {
            authService.xacMinhEmail(token);
        } catch (AppException ex) {
            // GIỮ NGUYÊN mã lỗi nghiệp vụ (400 cho link sai, 410 cho hết hạn...): chỉ đổi phần
            // thân trang từ JSON sang HTML. Đổi luôn mã thành 200 thì người dùng và giám sát
            // đều tưởng link hỏng là thành công.
            return ResponseEntity.status(ex.getErrorCode().getStatus())
                    .body(loiTrang(ex, "Nếu bạn vừa đăng ký mà chưa nhận được mail, "
                            + "bấm nút gửi lại xác minh ở trang đăng nhập."));
        }
        return ResponseEntity.ok(
                trang("Đã xác minh email", "Xong rồi. Quay lại ứng dụng và đăng nhập bằng mật khẩu của bạn."));
    }

    /** Gửi lại mail xác minh. Email không có tài khoản cũng trả 200 — không lộ email nào đã đăng ký. */
    @PostMapping("/verify/resend")
    public ApiResponse<Void> guiLaiXacMinh(@RequestParam String email) {
        authService.guiLaiXacMinh(email);
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> dangNhap(@RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.dangNhap(req));
    }

    /**
     * Tôi là ai. Cần token (không nằm trong permitAll) và CỐ Ý trả vai/gói lấy từ database,
     * không giải mã JWT — token cũ có thể đã bị thu hồi hoặc vai đã đổi từ lúc phát.
     */
    @GetMapping("/me")
    public ApiResponse<AccountResponse> cuaToi(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(authService.cuaToi(taiKhoan(jwt)));
    }

    /**
     * Đổi mật khẩu khi đang đăng nhập. Trả token mới vì đổi mật khẩu làm token cũ hết hiệu lực
     * (Account.tokenVersion) — không trả thì người dùng tự đá mình ra ngoài ngay khi bấm Lưu.
     */
    @PostMapping("/password/change")
    public ApiResponse<TokenResponse> doiMatKhau(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestBody DoiMatKhauRequest req) {
        return ApiResponse.ok(authService.doiMatKhau(taiKhoan(jwt), req));
    }

    private static UUID taiKhoan(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** Còn khung: chưa có OAuth client ID của Google nên chưa kiểm được idToken. */
    @PostMapping("/login/google")
    public ApiResponse<TokenResponse> dangNhapGoogle(@RequestBody LinkCredentialRequest req) {
        return ApiResponse.ok(authService.dangNhapGoogle(req.idToken()));
    }

    /** Luôn 200, kể cả khi email không tồn tại — xem chú thích ở AuthServiceImpl.quenMatKhau. */
    @PostMapping("/password/forgot")
    public ApiResponse<Void> quenMatKhau(@RequestBody ForgotPasswordRequest req) {
        authService.quenMatKhau(req);
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
    public String trangDatLaiMatKhau(@RequestParam String token) {
        return """
                <!doctype html>
                <html lang="vi"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Đặt lại mật khẩu — FinTeen</title></head>
                <body style="font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;line-height:1.6">
                <h1 style="font-size:1.4rem">Đặt lại mật khẩu</h1>
                <form method="post" action="reset">
                <input type="hidden" name="token" value="%s">
                <p><label>Mật khẩu mới<br>
                <input type="password" name="newPassword" minlength="8" required
                       autocomplete="new-password" style="width:100%%;padding:.5rem"></label></p>
                <p><button type="submit" style="padding:.5rem 1rem">Đổi mật khẩu</button></p>
                </form>
                <p style="color:#666;font-size:.9rem">Link hết hạn sau 30 phút và chỉ dùng được một lần.</p>
                </body></html>
                """.formatted(escapeHtml(token));
    }

    @PostMapping(value = "/password/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> datLaiMatKhau(@RequestBody ResetPasswordRequest req) {
        authService.datLaiMatKhau(req);
        return ApiResponse.ok();
    }

    /**
     * Biến thể form-urlencoded cho trang ở trên: trình duyệt gửi form, không có ứng dụng JS nào để
     * dựng JSON. Trả HTML để người dùng thấy kết quả ngay tại chỗ, không bị ném về JSON lạnh lẽo.
     * Lỗi vẫn ném ra GlobalExceptionHandler như mọi endpoint khác.
     */
    @PostMapping(value = "/password/reset", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> datLaiMatKhauQuaForm(@RequestParam String token, @RequestParam String newPassword) {
        try {
            authService.datLaiMatKhau(new ResetPasswordRequest(token, newPassword));
        } catch (AppException ex) {
            // Mật khẩu ngắn, link hết hạn, link đã dùng: tất cả đều là người dùng đang đứng
            // trên trang HTML này. Ném ra GlobalExceptionHandler là y chang lỗi A5 — JSON 400
            // hiện trên màn hình trắng.
            return ResponseEntity.status(ex.getErrorCode().getStatus())
                    .body(loiTrang(ex, "Bấm lại link trong mail để lấy link mới, "
                            + "hoặc dùng nút 'quên mật khẩu' để xin lại."));
        }
        return ResponseEntity.ok(trang("Đã đổi mật khẩu", "Đăng nhập bằng mật khẩu vừa đặt."));
    }

    /**
     * Trang HTML cho lỗi nghiệp vụ ở các endpoint trả HTML. Khác với GlobalExceptionHandler:
     * chỗ đó dựng ApiResponse JSON cho FE, còn đây người dùng đang nhìn trình duyệt.
     * Chạy qua escapeHtml vì message có thể mang nội dung người dùng nhập.
     */
    private static String loiTrang(AppException ex, String huongDan) {
        return trang("Không hoàn tất được",
                escapeHtml(ex.getMessage()) + " " + huongDan);
    }

    /** Nhét giá trị từ người dùng vào HTML thì phải thoát — token nằm trong thuộc tính value. */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Trang tĩnh sau khi bấm link. Cố ý trả 200 với lời nhắc chứ không tự redirect: BE chưa biết
     * địa chỉ trang đăng nhập của FE, mà đoán bừa thì hỏng ngay khi FE đổi tên trang.
     */
    private static String trang(String tieuDe, String noiDung) {
        return """
                <!doctype html>
                <html lang="vi"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s — FinTeen</title></head>
                <body style="font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;line-height:1.6">
                <h1 style="font-size:1.4rem">%s</h1>
                <p>%s</p>
                </body></html>
                """.formatted(tieuDe, tieuDe, noiDung);
    }
}