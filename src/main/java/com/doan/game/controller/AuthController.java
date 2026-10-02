package com.doan.game.controller;

import com.doan.game.DTO.request.ForgotPasswordRequest;
import com.doan.game.DTO.request.LinkCredentialRequest;
import com.doan.game.DTO.request.LoginRequest;
import com.doan.game.DTO.request.RegisterRequest;
import com.doan.game.DTO.request.ResetPasswordRequest;
import com.doan.game.DTO.response.AccountResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.service.AuthService;
import com.doan.game.service.impl.AuthServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    /** Chỉ để gọi guiLaiXacMinh — hàm này không nằm trong interface vì chưa có endpoint chốt. */
    private final AuthServiceImpl authServiceImpl;

    @PostMapping("/register")
    public ApiResponse<AccountResponse> dangKy(@RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.dangKy(req));
    }

    /**
     * Link người dùng bấm từ hộp thư, không có token ở đó nên đường này permitAll.
     * Trả HTML chứ không trả JSON: đây là trình duyệt đang mở, không phải FE gọi bằng fetch.
     */
    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String xacMinhEmail(@RequestParam String token) {
        authService.xacMinhEmail(token);
        return trang("Đã xác minh email", "Xong rồi. Quay lại ứng dụng và đăng nhập bằng mật khẩu của bạn.");
    }

    /** Gửi lại mail xác minh. Email không có tài khoản cũng trả 200 — không lộ email nào đã đăng ký. */
    @PostMapping("/verify/resend")
    public ApiResponse<Void> guiLaiXacMinh(@RequestParam String email) {
        authServiceImpl.guiLaiXacMinh(email);
        return ApiResponse.ok();
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> dangNhap(@RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.dangNhap(req));
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
                <form method="post" action="/api/auth/password/reset">
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
    public String datLaiMatKhauQuaForm(@RequestParam String token, @RequestParam String newPassword) {
        authService.datLaiMatKhau(new ResetPasswordRequest(token, newPassword));
        return trang("Đã đổi mật khẩu", "Đăng nhập bằng mật khẩu vừa đặt.");
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