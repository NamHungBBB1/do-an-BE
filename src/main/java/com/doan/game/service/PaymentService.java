package com.doan.game.service;

import com.doan.game.DTO.request.BuyPlanRequest;
import com.doan.game.DTO.response.PaymentResponse;
import com.doan.game.DTO.response.PaymentStatusResponse;
import com.doan.game.DTO.response.TransactionAdminResponse;
import com.doan.game.enums.TransactionStatus;

import java.util.List;
import java.util.UUID;

/**
 * Thanh toán qua PayOS (SDK chính chủ, bean PayOS trong configuration/PayOsConfig).
 *
 * Tạo link mua mới hoặc gia hạn (giá đọc từ Plan do admin đặt; orderCode do mình sinh, Transaction
 * PENDING); xem / huỷ theo orderCode; webhook: verify chữ ký, PAID thì cấp Entitlement (gia hạn sớm
 * nối tiếp hạn cũ), trùng thì bỏ qua, controller LUÔN trả 200; GET trạng thái (FE poll sau khi hiện QR) cũng
 * chủ động hỏi lại PayOS; cron đối soát mọi đơn PENDING; admin đăng ký URL webhook, xem bảng giao dịch, đối soát
 * một giao dịch. Không có trang returnUrl / cancelUrl: QR hiện ngay trong app (Hưng chốt 07/10).
 *
 * Bảng phụ trách: Transaction, Entitlement (phần cấp từ thanh toán)
 */
public interface PaymentService {

    PaymentResponse createPayment(UUID accountId, BuyPlanRequest req);

    /** Chủ giao dịch xem trạng thái; PENDING thì hỏi lại PayOS ngay (FE poll tới khi PAID). */
    PaymentStatusResponse getPayment(UUID accountId, long orderCode);

    /** Lịch sử giao dịch của chính người gọi, mới nhất trước. */
    List<TransactionAdminResponse> listMyTransactions(UUID accountId, int page, int size);

    void cancelPayment(UUID accountId, long orderCode, String reason);

    /** Nhận body thô của webhook PayOS. Không bao giờ ném ra ngoài: PayOS cần 200. */
    void handleWebhook(String body);

    /** Đăng ký URL webhook với PayOS; để trống thì dùng app.payos.webhook-url. Trả về URL đã đăng ký. */
    String confirmWebhook(String url);

    void sweepPendingTransactions();

    /** Admin: bảng giao dịch, mới nhất trước; status null = tất cả. */
    List<TransactionAdminResponse> listTransactions(TransactionStatus status, int page, int size);

    /** Admin: hỏi lại PayOS cho một giao dịch (chỉ có tác dụng khi còn PENDING). */
    PaymentStatusResponse reconcile(long orderCode);
}
