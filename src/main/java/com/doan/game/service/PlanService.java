package com.doan.game.service;

import com.doan.game.DTO.request.SetPlanPriceRequest;
import com.doan.game.DTO.response.PlanResponse;

import java.util.List;
import java.util.UUID;

/**
 * Giá bán và số tháng của hai gói, admin đặt trên web (02/10: không để giá trong cấu hình máy chủ).
 * Chưa có dòng thì chưa bán được gói đó. PaymentService đọc giá ở đây lúc tạo giao dịch; Transaction
 * chép giá lúc mua nên đổi giá sau không ảnh hưởng giao dịch cũ.
 *
 * Bảng phụ trách: Plan
 */
public interface PlanService {

    /** Bảng giá công khai, không cần đăng nhập. */
    List<PlanResponse> xemGia();

    /** Admin đặt giá (và tuỳ chọn số tháng) cho một gói; chưa có thì tạo, có rồi thì sửa. */
    PlanResponse datGia(UUID adminId, String kind, SetPlanPriceRequest req);
}
