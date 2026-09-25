package com.doan.game.service.impl;

import com.doan.game.service.PaymentService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Giao dịch qua cổng thanh toán và webhook bật vai.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    @Override
    public PaymentResponse taoGiaoDich(UUID accountId, BuyPackageRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void nhanWebhook(String chuKy, String noiDung) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
