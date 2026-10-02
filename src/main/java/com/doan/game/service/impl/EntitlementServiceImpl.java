package com.doan.game.service.impl;

import com.doan.game.service.EntitlementService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Gói đang giữ. Parent / Teacher KHÔNG lưu ở Account mà suy từ Entitlement còn hạn, tính theo NGÀY giờ Việt Nam; mọi thao tác cần gói đều gọi lại activePlans, không tin vai trong JWT. Admin cấp được gói không cần thanh toán.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    @Override
    public java.util.Set<com.doan.game.enums.PlanKind> activePlans(UUID accountId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public java.util.List<EntitlementResponse> xemGoi(UUID accountId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public EntitlementResponse capGoi(UUID adminId, GrantPlanRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void nhacSapHetHan(java.time.LocalDate homNay) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
