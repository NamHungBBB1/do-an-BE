package com.doan.game.service;

import com.doan.game.DTO.request.BuyPlanRequest;
import com.doan.game.DTO.response.PaymentResponse;
import com.doan.game.DTO.response.PaymentStatusResponse;

import java.util.UUID;

/**
 * Thanh toán qua PayOS (SDK chính chủ, bean PayOS trong configuration/PayOsConfig).
 *
 * Tạo link mua mới hoặc gia hạn (orderCode do mình sinh, Transaction PENDING); xem / huỷ theo
 * orderCode; webhook: verify chữ ký, PAID thì cấp Entitlement (gia hạn sớm nối tiếp hạn cũ),
 * trùng thì bỏ qua, controller LUÔN trả 200; returnUrl cũng chủ động hỏi lại PayOS; cron quét
 * PENDING quá hạn; admin đăng ký URL webhook.
 *
 * Bảng phụ trách: Transaction, Entitlement (phần cấp từ thanh toán)
 */
public interface PaymentService {

    PaymentResponse taoGiaoDich(UUID accountId, BuyPlanRequest req);

    /** Chủ giao dịch xem trạng thái; PENDING thì hỏi lại PayOS ngay (đường returnUrl). */
    PaymentStatusResponse xemGiaoDich(UUID accountId, long orderCode);

    void huyGiaoDich(UUID accountId, long orderCode, String lyDo);

    /** Nhận body thô của webhook PayOS. Không bao giờ ném ra ngoài: PayOS cần 200. */
    void nhanWebhook(String body);

    /** Đăng ký URL webhook với PayOS; để trống thì dùng app.payos.webhook-url. Trả về URL đã đăng ký. */
    String xacNhanWebhook(String url);

    void quetGiaoDichTreo();
}
