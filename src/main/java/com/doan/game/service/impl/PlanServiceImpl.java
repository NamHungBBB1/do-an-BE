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
        if (req.months() != null && req.months() <= 0) {
            throw new AppException(ErrorCode.PLAN_PRICE_INVALID, "months phải > 0");
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
