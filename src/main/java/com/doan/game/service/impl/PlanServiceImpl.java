package com.doan.game.service.impl;

import com.doan.game.DTO.request.SetPlanPriceRequest;
import com.doan.game.DTO.response.PlanResponse;
import com.doan.game.entity.Plan;
import com.doan.game.entity.SlotAddonPrice;
import com.doan.game.enums.PlanKind;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.PlanRepository;
import com.doan.game.repository.SlotAddonPriceRepository;
import com.doan.game.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bảng giá do admin đặt (18/11/2026): gói gốc VĨNH VIỄN nên không còn số tháng; pack slot có bảng
 * giá riêng ({@link SlotAddonPrice}). Số slot mỗi pack là hằng số nghiệp vụ dưới đây, không phải
 * dữ liệu admin sửa.
 */
@Service
@RequiredArgsConstructor
public class PlanServiceImpl implements PlanService {

    /** Một pack Parent cộng 2 slot (chốt Q2). */
    public static final int PARENT_ADDON_SLOTS = 2;
    /** Một pack Teacher cộng 20 slot (chốt Q3). */
    public static final int TEACHER_ADDON_SLOTS = 20;
    /** Trần nghiệp vụ cho giá một gói / một pack (P-09): gõ nhầm thêm số 0 là mọi người mua đều 4004 từ PayOS. */
    public static final long MAX_PRICE_VND = 50_000_000L;

    private final PlanRepository planRepo;
    private final SlotAddonPriceRepository addonPriceRepo;
    private final AccountRepository accountRepo;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<PlanResponse> listPrices() {
        // Luôn trả ĐỦ hai loại gói: chưa đặt giá thì price = 0 (chưa mở bán), FE khỏi phải đoán
        // thiếu dòng nghĩa là gì; giá pack vẫn có thể đã đặt trước giá gói.
        return java.util.Arrays.stream(PlanKind.values())
                .sorted()
                .map(k -> planRepo.findByKind(k)
                        .map(this::toResponse)
                        .orElseGet(() -> new PlanResponse(k.name(), 0L, addonSlotsOf(k),
                                addonPriceRepo.findByKind(k).map(SlotAddonPrice::getPrice).orElse(null),
                                null)))
                .toList();
    }

    @Override
    @Transactional
    public PlanResponse setPrice(UUID adminId, String kind, SetPlanPriceRequest req) {
        PlanKind k = parseKind(kind);
        long price = requirePrice(req);
        Plan plan = planRepo.findByKind(k).orElseGet(() -> {
            Plan p = new Plan();
            p.setKind(k);
            return p;
        });
        plan.setPrice(price);
        plan.setUpdatedAt(Instant.now(clock));
        plan.setUpdatedBy(accountRepo.getReferenceById(adminId));
        return toResponse(planRepo.save(plan));
    }

    @Override
    @Transactional
    public PlanResponse setAddonPrice(UUID adminId, String kind, SetPlanPriceRequest req) {
        PlanKind k = parseKind(kind);
        long price = requirePrice(req);
        SlotAddonPrice row = addonPriceRepo.findByKind(k).orElseGet(() -> {
            SlotAddonPrice p = new SlotAddonPrice();
            p.setKind(k);
            return p;
        });
        row.setPrice(price);
        row.setUpdatedAt(Instant.now(clock));
        row.setUpdatedBy(accountRepo.getReferenceById(adminId));
        addonPriceRepo.save(row);
        // Trả cả bảng giá của gói để FE cập nhật một lần (giá gói có thể chưa đặt — khi đó price = 0).
        return planRepo.findByKind(k).map(this::toResponse)
                .orElse(new PlanResponse(k.name(), 0L, addonSlotsOf(k), price, null));
    }

    static PlanKind parseKind(String kind) {
        try {
            return PlanKind.valueOf(kind == null ? "" : kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.PLAN_KIND_INVALID, String.valueOf(kind));
        }
    }

    /** Số slot cộng thêm của MỘT pack theo loại gói. */
    public static int addonSlotsOf(PlanKind kind) {
        return kind == PlanKind.PARENT ? PARENT_ADDON_SLOTS : TEACHER_ADDON_SLOTS;
    }

    private static long requirePrice(SetPlanPriceRequest req) {
        if (req == null || req.price() <= 0) {
            throw new AppException(ErrorCode.PLAN_PRICE_INVALID);
        }
        if (req.price() > MAX_PRICE_VND) {
            throw new AppException(ErrorCode.PLAN_PRICE_INVALID, "price tối đa " + MAX_PRICE_VND + " đ");
        }
        return req.price();
    }

    private PlanResponse toResponse(Plan p) {
        PlanKind kind = p.getKind();
        Long addonPrice = addonPriceRepo.findByKind(kind).map(SlotAddonPrice::getPrice).orElse(null);
        return new PlanResponse(kind.name(), p.getPrice() == null ? 0L : p.getPrice(),
                addonSlotsOf(kind), addonPrice, p.getUpdatedAt());
    }
}
