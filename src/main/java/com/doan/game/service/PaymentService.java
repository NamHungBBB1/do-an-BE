package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * PayOS qua SDK chính chủ (bean PayOS trong configuration/PayOsConfig). Tạo link (orderCode do mình sinh, lưu Transaction PENDING); xem / huỷ theo orderCode; webhook: client.webhooks().verify(body) rồi PAID thì tạo Entitlement (gia hạn sớm nối tiếp hạn cũ), trùng thì bỏ qua, LUÔN trả 200; returnUrl cũng chủ động hỏi lại PayOS; cron quét PENDING quá hạn; admin đăng ký URL webhook bằng webhooks().confirm.
 *
 * Bảng phụ trách: Transaction
 */
public interface PaymentService {

    PaymentResponse taoGiaoDich(UUID accountId, BuyPlanRequest req);

    PaymentStatusResponse xemGiaoDich(long orderCode);

    void huyGiaoDich(long orderCode, String lyDo);

    void nhanWebhook(String body);

    String xacNhanWebhook(String url);

    void quetGiaoDichTreo();

}
