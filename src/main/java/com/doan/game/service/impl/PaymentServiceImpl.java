package com.doan.game.service.impl;

import com.doan.game.service.PaymentService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * PayOS qua SDK chính chủ (bean PayOS trong configuration/PayOsConfig). Tạo link (orderCode do mình sinh, lưu Transaction PENDING); xem / huỷ theo orderCode; webhook: client.webhooks().verify(body) rồi PAID thì tạo Entitlement (gia hạn sớm nối tiếp hạn cũ), trùng thì bỏ qua, LUÔN trả 200; returnUrl cũng chủ động hỏi lại PayOS; cron quét PENDING quá hạn; admin đăng ký URL webhook bằng webhooks().confirm.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    @Override
    public PaymentResponse taoGiaoDich(UUID accountId, BuyPlanRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public PaymentStatusResponse xemGiaoDich(long orderCode) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void huyGiaoDich(long orderCode, String lyDo) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void nhanWebhook(String body) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public String xacNhanWebhook(String url) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void quetGiaoDichTreo() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
