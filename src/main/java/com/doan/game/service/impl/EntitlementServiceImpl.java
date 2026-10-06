package com.doan.game.service.impl;

import com.doan.game.DTO.request.GrantPlanRequest;
import com.doan.game.DTO.response.EntitlementResponse;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.PlanKind;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.EntitlementMapper;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.service.EntitlementFactory;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Gói đang giữ. Parent / Teacher KHÔNG lưu ở Account mà suy từ Entitlement còn hạn, tính theo NGÀY giờ Việt Nam; mọi thao tác cần gói đều gọi lại activePlans, không tin vai trong JWT. Admin cấp được gói không cần thanh toán.
 *
 * CÓ RUỘT cả ba hàm (06/10):
 * - activePlans (02/10): nơi duy nhất quyết định một tài khoản có gói hay không, nên nó phải chạy
 *   được TRƯỚC khi AuthService phát token. activePlans xong thì AccountResponse và scope
 *   PARENT / TEACHER mới lấp được.
 * - listPlans: lịch sử gói của chính người gọi, cả gói hết hạn.
 * - grantPlan: admin cấp tay, dựng dòng qua EntitlementFactory — cùng một quy tắc ngày với payment.
 *
 * Phần remindExpiringPlans vẫn là KHUNG — ném UnsupportedOperationException để không ai vô tình
 * dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {

    /** Số tháng tối đa cho một lần cấp tay (Hưng chốt 06/10: 1–36). */
    static final int MAX_GRANT_MONTHS = 36;

    private final EntitlementRepository entitlementRepo;
    private final AccountRepository accountRepo;
    private final EntitlementFactory entitlementFactory;
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

    /**
     * Lịch sử gói của chính người gọi, hạn mới nhất trước. Trả cả gói hết hạn — còn hạn hay không
     * là việc của activePlans (/api/auth/me), ở đây người dùng muốn xem đã mua / được cấp gì.
     */
    @Override
    @Transactional(readOnly = true)
    public List<EntitlementResponse> listPlans(UUID accountId) {
        return entitlementRepo.findByAccount_IdOrderByExpiresOnDescCreatedAtDesc(accountId).stream()
                .map(EntitlementMapper::toResponse)
                .toList();
    }

    /**
     * Admin cấp gói không qua thanh toán (dùng thử, đền bù). Nghiệp vụ nằm hết ở đây để controller
     * chỉ nhận và gọi: kiểm dữ liệu, tìm hai tài khoản, dựng dòng qua EntitlementFactory
     * (source = ADMIN, grantedBy = admin, transaction = null — Hưng chốt 06/10).
     */
    @Override
    @Transactional
    public EntitlementResponse grantPlan(UUID adminId, GrantPlanRequest req) {
        if (req.accountId() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "accountId: bắt buộc");
        }
        PlanKind kind = parsePlanKind(req.kind());
        if (req.months() < 1 || req.months() > MAX_GRANT_MONTHS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "months: phải từ 1 đến " + MAX_GRANT_MONTHS);
        }
        if (req.reason() == null || req.reason().isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "reason: bắt buộc");
        }
        Account recipient = accountRepo.findById(req.accountId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản " + req.accountId()));
        Account admin = accountRepo.findById(adminId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản admin " + adminId));

        Entitlement entitlement = entitlementFactory.create(recipient, kind, req.months(),
                EntitlementSource.ADMIN, null, admin, req.reason().trim());
        entitlementRepo.save(entitlement);
        return EntitlementMapper.toResponse(entitlement);
    }

    @Override
    public void remindExpiringPlans(LocalDate today) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    private static PlanKind parsePlanKind(String kind) {
        if (kind == null) {
            throw new AppException(ErrorCode.PLAN_KIND_INVALID);
        }
        try {
            return PlanKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.PLAN_KIND_INVALID);
        }
    }

}
