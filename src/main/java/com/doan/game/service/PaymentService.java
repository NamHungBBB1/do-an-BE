package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Giao dịch qua cổng thanh toán và webhook bật vai.
 *
 * Bảng phụ trách: Transaction
 */
public interface PaymentService {

    PaymentResponse taoGiaoDich(UUID accountId, BuyPackageRequest req);

    void nhanWebhook(String chuKy, String noiDung);

}
