package com.doan.game.service.impl;

import com.doan.game.DTO.request.SetPlanPriceRequest;
import com.doan.game.DTO.response.PlanResponse;
import com.doan.game.entity.Plan;
import com.doan.game.enums.PlanKind;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.PlanRepository;
import com.doan.game.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanServiceImpl implements PlanService {

    /** Thời hạn mặc định khi admin đặt giá lần đầu mà không nói số tháng (quyết định 01/10: gói 3 tháng). */
    static final int DEFAULT_MONTHS = 3;

    private final PlanRepository planRepo;
    private final AccountRepository accountRepo;
    private final Clock clock;
    /** Trần nghiệp vụ cho giá một gói (P-09): gõ nhầm thêm số 0 là mọi người mua đều 4004 từ PayOS. */
    static final long MAX_PRICE_VND = 50_000_000L;

    @Override
    @Transactional(readOnly = true)
    public List<PlanResponse> listPrices() {
        return planRepo.findAll().stream()
                .sorted(Comparator.comparing(Plan::getKind))
                .map(PlanServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public PlanResponse setPrice(UUID adminId, String kind, SetPlanPriceRequest req) {
        PlanKind k = parseKind(kind);
        if (req == null || req.price() <= 0) {
            throw new AppException(ErrorCode.PLAN_PRICE_INVALID);
        }
        if (req.price() > MAX_PRICE_VND) {
            throw new AppException(ErrorCode.PLAN_PRICE_INVALID, "price tối đa " + MAX_PRICE_VND + " đ");
        }
        if (req.months() != null && (req.months() <= 0 || req.months() > EntitlementServiceImpl.MAX_GRANT_MONTHS)) {
            throw new AppException(ErrorCode.PLAN_PRICE_INVALID,
                    "months phải từ 1 đến " + EntitlementServiceImpl.MAX_GRANT_MONTHS);
        }
        Plan plan = planRepo.findByKind(k).orElseGet(() -> {
            Plan p = new Plan();
            p.setKind(k);
            p.setMonths(DEFAULT_MONTHS);
            return p;
        });
        plan.setPrice(req.price());
        if (req.months() != null) {
            plan.setMonths(req.months());
        }
        plan.setUpdatedAt(Instant.now(clock));
        plan.setUpdatedBy(accountRepo.getReferenceById(adminId));
        return toResponse(planRepo.save(plan));
    }

    static PlanKind parseKind(String kind) {
        try {
            return PlanKind.valueOf(kind == null ? "" : kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.PLAN_KIND_INVALID, String.valueOf(kind));
        }
    }

    private static PlanResponse toResponse(Plan p) {
        return new PlanResponse(p.getKind().name(), p.getPrice(), p.getMonths(), p.getUpdatedAt());
    }
}
