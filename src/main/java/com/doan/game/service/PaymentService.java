package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Giao dịch PayOS: tạo giao dịch mua mới hoặc gia hạn; webhook PAID thì tạo Entitlement (gia hạn sớm nối tiếp hạn cũ); trạng thái chỉ chuyển tiến, webhook trùng bỏ qua; cron quét giao dịch PENDING quá hạn.
 *
 * Bảng phụ trách: Transaction
 */
public interface PaymentService {

    PaymentResponse taoGiaoDich(UUID accountId, BuyPlanRequest req);

    void nhanWebhook(String chuKy, String noiDung);

    void quetGiaoDichTreo();

}
