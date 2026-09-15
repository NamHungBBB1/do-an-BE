package com.doan.game.auth.web;

import com.doan.game.auth.AuthService;
import com.doan.game.auth.JwtService;
import com.doan.game.auth.MailService;
import com.doan.game.auth.domain.Account;
import com.doan.game.auth.domain.ChildSlot;
import com.doan.game.auth.domain.Role;
import com.doan.game.auth.web.AuthDtos.*;
import com.doan.game.shared.error.AppException;
import com.doan.game.shared.error.ErrorCode;
import com.doan.game.shared.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cửa vào duy nhất của hệ thống.
 *
 * Hai đường hoàn toàn tách nhau, đúng như biên bản đã chốt:
 *   NGƯỜI LỚN  — Gmail + mật khẩu, gói đã mua quyết định vai.
 *   TRẺ        — mã QR + PIN do người lớn phát, hoặc tài khoản đã liên kết.
 *
 * Trẻ có cả nhà lẫn lớp thì KHÔNG gộp: đăng nhập xong phải CHỌN bối cảnh
 * (POST /api/auth/context), mỗi bối cảnh một token riêng.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final JwtService jwt;
    private final MailService mail;
    private final String baseUrl;

    public AuthController(AuthService auth, JwtService jwt, MailService mail,
                          @Value("${app.public-base-url}") String baseUrl) {
        this.auth = auth;
        this.jwt = jwt;
        this.mail = mail;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /**
     * Đăng ký xong là CÓ token ngay, nhưng tài khoản chưa xác thực thì chưa mua được gói
     * và chưa tạo được slot nào. Trả token luôn để FE hiện được màn "vào hộp thư đi"
     * thay vì bắt đăng nhập lại.
     */
    @PostMapping("/register")
    public ApiResponse<TokenView> register(@RequestBody @Valid RegisterRequest r) {
        Account a = auth.register(r.email(), r.phone(), r.password(), r.displayName());
        sendVerification(a);
        return ApiResponse.ok(token(jwt.forAdult(a), "adult"));
    }

    /** Link trong mail trỏ thẳng vào đây, nên trả HTML chứ không phải JSON. */
    @GetMapping(value = "/verify", produces = MediaType.TEXT_HTML_VALUE)
    public String verify(@RequestParam String token) {
        try {
            Account a = auth.verifyEmail(token);
            return page("✅", "Xác thực xong",
                    "Tài khoản <b>" + esc(a.getEmail()) + "</b> đã sẵn sàng. Quay lại ứng dụng và đăng nhập.");
        } catch (AppException e) {
            // Người dùng cuối đang đứng ở trình duyệt — trả trang đọc được, đừng trả JSON lỗi.
            return page("⚠️", "Không xác thực được", esc(e.getMessage()));
        }
    }

    /** Mất mail, mail vào spam, hoặc link hết hạn thì bấm lại từ đây. */
    @PostMapping("/verify/resend")
    public ApiResponse<Void> resend(@AuthenticationPrincipal Jwt j) {
        sendVerification(auth.require(adultId(j)));
        return ApiResponse.ok();
    }

    private void sendVerification(Account a) {
        String token = auth.issueVerification(a.getId());
        String link = baseUrl + "/api/auth/verify?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        mail.sendVerification(a.getEmail(), a.getDisplayName(), link);
    }

    @PostMapping("/login")
    public ApiResponse<TokenView> login(@RequestBody @Valid LoginRequest r) {
        Account a = auth.login(r.email(), r.password());
        return ApiResponse.ok(token(jwt.forAdult(a), "adult"));
    }

    /** Trẻ vào bằng mã QR + PIN. Token trả về đã gắn chặt vào đúng một slot, một bối cảnh. */
    @PostMapping("/child/login")
    public ApiResponse<TokenView> childLogin(@RequestBody @Valid ChildLoginRequest r) {
        ChildSlot s = auth.childLogin(r.code(), r.pin());
        return ApiResponse.ok(token(jwt.forChild(s), "child"));
    }

    /**
     * Gắn slot vào tài khoản của trẻ. Gọi khi ĐANG đăng nhập bằng tài khoản,
     * và phải kèm QR + PIN để chứng minh đang thật sự giữ slot đó.
     */
    @PostMapping("/child/link")
    public ApiResponse<SlotView> link(@AuthenticationPrincipal Jwt j,
                                      @RequestBody @Valid ChildLoginRequest r) {
        ChildSlot s = auth.link(adultId(j), r.code(), r.pin());
        return ApiResponse.ok(SlotView.of(s));
    }

    /**
     * Chọn bối cảnh để chơi tiếp. Đây là hiện thân của chốt "bé có cả family lẫn class
     * thì cho bé CHỌN" — không có đường nào gộp hai bối cảnh vào một phiên.
     */
    @PostMapping("/context")
    public ApiResponse<TokenView> enterContext(@AuthenticationPrincipal Jwt j,
                                               @RequestBody @Valid EnterContextRequest r) {
        ChildSlot s = auth.enterContext(adultId(j), r.slotId());
        return ApiResponse.ok(token(jwt.forChild(s), "child"));
    }

    /**
     * Bật gói. Chưa nối thanh toán — xem ghi chú trong AuthService.setPlans.
     *
     * Trả về token MỚI chứ không phải hồ sơ: vai nằm trong token, mua gói xong mà FE
     * vẫn giữ token cũ thì vẫn bị chặn ở /api/slots và không ai hiểu vì sao.
     */
    @PostMapping("/plan")
    public ApiResponse<TokenView> plan(@AuthenticationPrincipal Jwt j,
                                       @RequestBody @Valid PlanRequest r) {
        Account a = auth.setPlans(adultId(j), r.parentPlan(), r.teacherPlan());
        return ApiResponse.ok(token(jwt.forAdult(a), "adult"));
    }

    /** Một endpoint, hai hình dạng — vì người lớn và trẻ nhìn thấy hai thứ khác hẳn nhau. */
    @GetMapping("/me")
    public ApiResponse<?> me(@AuthenticationPrincipal Jwt j) {
        if ("child".equals(j.getClaimAsString("typ"))) {
            ChildSlot s = auth.requireSlot(UUID.fromString(j.getSubject()));
            return ApiResponse.ok(new ChildMeView(s.getId(), s.getDisplayName(), s.getContext(),
                    s.getLevel(), s.getBadge(), s.getLinkedAccountId() != null));
        }
        return adultMe(adultId(j));
    }

    private ApiResponse<AdultMeView> adultMe(UUID id) {
        Account a = auth.require(id);
        Set<String> roles = a.roles().stream().map(Role::name).collect(Collectors.toSet());
        List<SlotView> owned = auth.slotsOf(id).stream().map(SlotView::of).toList();
        // Cùng một tài khoản có thể vừa sở hữu slot (là người lớn) vừa được liên kết
        // slot (là trẻ đã tạo tài khoản). Hai danh sách tách riêng, không trộn.
        List<SlotView> linked = auth.contextsOf(id).stream().map(SlotView::of).toList();
        return ApiResponse.ok(new AdultMeView(a.getId(), a.getEmail(), a.getPhone(),
                a.getDisplayName(), a.isEmailVerified(), roles, owned, linked));
    }

    /** Token của trẻ không được dùng cho các cửa của người lớn. */
    static UUID adultId(Jwt j) {
        if (!"adult".equals(j.getClaimAsString("typ"))) throw new AppException(ErrorCode.FORBIDDEN);
        return UUID.fromString(j.getSubject());
    }

    private static TokenView token(JwtService.Issued i, String typ) {
        return new TokenView(i.token(), typ, i.expiresIn());
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Trang tối giản cho người bấm link từ hộp thư. Không tải font ngoài, không JS. */
    private static String page(String icon, String title, String body) {
        return """
                <!doctype html><html lang="vi"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s — FinTeen</title>
                <div style="font-family:'Segoe UI',Roboto,Arial,sans-serif;max-width:32rem;margin:18vh auto;
                     padding:0 1.5rem;text-align:center;color:#1f2937">
                  <div style="font-size:3rem;line-height:1">%s</div>
                  <h1 style="font-size:1.35rem;margin:.75rem 0 .5rem">%s</h1>
                  <p style="color:#6b7280;line-height:1.6">%s</p>
                </div>""".formatted(title, icon, title, body);
    }
}
