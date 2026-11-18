package com.doan.game.service;

import com.doan.game.DTO.request.SetPlanPriceRequest;
import com.doan.game.DTO.response.PlanResponse;

import java.util.List;
import java.util.UUID;

/**
 * Giá bán của hai gói và giá một pack slot thêm, admin đặt trên web (02/10: không để giá trong cấu
 * hình máy chủ). 18/11/2026: gói bán ra là VĨNH VIỄN nên bỏ số tháng; pack là đơn vị bán của slot
 * thêm (Parent +2, Teacher +20). Chưa có dòng giá thì chưa bán được → 4002. PaymentService đọc giá
 * ở đây lúc tạo giao dịch; Transaction chép giá lúc mua nên đổi giá sau không ảnh hưởng đơn cũ.
 *
 * Bảng phụ trách: Plan, SlotAddonPrice
 */
public interface PlanService {

    /** Bảng giá công khai, không cần đăng nhập. */
    List<PlanResponse> listPrices();

    /** Admin đặt giá gói gốc (vĩnh viễn); chưa có thì tạo, có rồi thì sửa. */
    PlanResponse setPrice(UUID adminId, String kind, SetPlanPriceRequest req);

    /** Admin đặt giá MỘT pack slot thêm của loại gói; chưa có thì tạo, có rồi thì sửa. */
    PlanResponse setAddonPrice(UUID adminId, String kind, SetPlanPriceRequest req);
}
