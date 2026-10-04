package com.doan.game.service.impl;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import com.doan.game.enums.PlanKind;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Gói đang giữ. Parent / Teacher KHÔNG lưu ở Account mà suy từ Entitlement còn hạn, tính theo NGÀY giờ Việt Nam; mọi thao tác cần gói đều gọi lại activePlans, không tin vai trong JWT. Admin cấp được gói không cần thanh toán.
 *
 * CÓ RUỘT phần activePlans (02/10, làm cùng đợt AuthService): đây là nơi duy nhất quyết định
 * một tài khoản có gói hay không, nên nó phải chạy được TRƯỚC khi AuthService phát token.
 * activePlans xong thì AccountResponse và scope PARENT / TEACHER mới lấp được.
 *
 * Phần còn lại vẫn là KHUNG — mọi hàm chưa làm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    private final EntitlementRepository entitlementRepo;
    private final Clock clock;

    /**
     * Loại gói còn hiệu lực HÔM NAY. "Hôm nay" lấy từ Clock múi giờ Việt Nam, không phải UTC của
     * máy chủ — dùng đêm thì hai bên lệch nhau một ngày và gói vừa mua bị coi là chưa bắt đầu.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<PlanKind> activePlans(UUID accountId) {
        return entitlementRepo.findActivePlanKinds(accountId, LocalDate.now(clock));
    }

    @Override
    public java.util.List<EntitlementResponse> listPlans(UUID accountId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public EntitlementResponse grantPlan(UUID adminId, GrantPlanRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void remindExpiringPlans(java.time.LocalDate today) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
