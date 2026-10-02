package com.doan.game.controller;

import com.doan.game.DTO.request.BuyPlanRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.PaymentResponse;
import com.doan.game.DTO.response.PaymentStatusResponse;
import com.doan.game.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Cửa vào HTTP cho thanh toán. Tầng này MỎNG: nhận, gọi service, trả về. Có ruột (02/10), bộ sinh
 * khung giữ nguyên tệp này.
 *
 * Quy ước: subject của JWT là id tài khoản (UUID). Mọi controller cần "tôi là ai" đọc theo cách này.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;

    @PostMapping
    public ApiResponse<PaymentResponse> taoGiaoDich(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestBody BuyPlanRequest req) {
        return ApiResponse.ok(paymentService.taoGiaoDich(taiKhoan(jwt), req));
    }

    @GetMapping("/{orderCode}")
    public ApiResponse<PaymentStatusResponse> xemGiaoDich(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable long orderCode) {
        return ApiResponse.ok(paymentService.xemGiaoDich(taiKhoan(jwt), orderCode));
    }

    @PostMapping("/{orderCode}/cancel")
    public ApiResponse<Void> huyGiaoDich(@AuthenticationPrincipal Jwt jwt, @PathVariable long orderCode,
                                         @RequestParam(required = false) String lyDo) {
        paymentService.huyGiaoDich(taiKhoan(jwt), orderCode, lyDo);
        return ApiResponse.ok();
    }

    /**
     * PayOS gọi, không có JWT (SecurityConfig mở đường này). Luôn trả 200: PayOS chỉ coi URL là hợp lệ
     * khi nhận 200, và một lỗi của mình không phải lý do để PayOS gửi đi gửi lại. Đây là chỗ DUY NHẤT
     * controller bắt lỗi — vì giao thức bên kia đòi, không phải để dựng response.
     */
    @PostMapping("/webhook")
    public ApiResponse<Void> nhanWebhook(@RequestBody String body) {
        try {
            paymentService.nhanWebhook(body);
        } catch (Exception e) {
            log.error("Xử lý webhook PayOS lỗi — vẫn trả 200: {}", e.toString());
        }
        return ApiResponse.ok();
    }

    /** Admin (SecurityConfig). Body tuỳ chọn {"url": "..."}; để trống thì dùng app.payos.webhook-url. */
    @PostMapping("/webhook/confirm")
    public ApiResponse<String> xacNhanWebhook(@RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(paymentService.xacNhanWebhook(body == null ? null : body.get("url")));
    }

    private static UUID taiKhoan(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
