package com.doan.game.controller;

import com.doan.game.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public void taoGiaoDich() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/webhook")
    public void nhanWebhook() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
