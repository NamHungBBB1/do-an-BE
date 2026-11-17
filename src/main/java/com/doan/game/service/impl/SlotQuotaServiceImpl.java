package com.doan.game.service.impl;

import com.doan.game.enums.LearningContext;
import com.doan.game.enums.PlanKind;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.SlotAddonRepository;
import com.doan.game.service.SlotQuota;
import com.doan.game.service.SlotQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tính ví slot: base theo gói + add-on còn hạn − số slot đã tạo trong kỳ.
 *
 * Không có cột "đã dùng" và không có job reset (chốt Q5): kỳ gói lấy từ entitlement đang chạy, nên
 * sang kỳ mới phép đếm tự về 0. Đếm cả ARCHIVED lẫn WIPED vì slot đã mở là tiêu hao (Q6) — đó là lý do
 * countCreatedSince không lọc status.
 */
@Service
@RequiredArgsConstructor
public class SlotQuotaServiceImpl implements SlotQuotaService {

    private final EntitlementRepository entitlementRepo;
    private final SlotAddonRepository addonRepo;
    private final LearnerSlotRepository slotRepo;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public SlotQuota quota(UUID accountId, LearningContext context) {
        PlanKind kind = kindOf(context);
        LocalDate today = LocalDate.now(clock);
        LocalDate start = entitlementRepo.findActiveTermStart(accountId, kind, today);
        if (start == null) {
            // Không có gói đang chạy: ví rỗng. Nơi gọi đã chặn 3005 trước đó, đây là lưới an toàn.
            return new SlotQuota(kind, 0, 0, 0, null, null);
        }
        LocalDate end = entitlementRepo.findActiveTermEnd(accountId, kind, today);
        int extra = (int) addonRepo.sumActiveSlots(accountId, kind, today);
        // Mốc đầu kỳ theo giờ người dùng (VN), không phải UTC — slot tạo 00:30 giờ VN vẫn thuộc kỳ mới.
        Instant since = start.atStartOfDay(clock.getZone()).toInstant();
        int used = (int) slotRepo.countCreatedSince(accountId, context, since);
        return new SlotQuota(kind, baseOf(kind), extra, used, start, end);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SlotQuota> quotas(UUID accountId) {
        Set<PlanKind> active = entitlementRepo.findActivePlanKinds(accountId, LocalDate.now(clock));
        List<SlotQuota> result = new ArrayList<>(2);
        if (active.contains(PlanKind.PARENT)) {
            result.add(quota(accountId, LearningContext.FAMILY));
        }
        if (active.contains(PlanKind.TEACHER)) {
            result.add(quota(accountId, LearningContext.CLASS));
        }
        return result;
    }

    static PlanKind kindOf(LearningContext context) {
        return context == LearningContext.FAMILY ? PlanKind.PARENT : PlanKind.TEACHER;
    }

    static int baseOf(PlanKind kind) {
        return kind == PlanKind.PARENT ? PARENT_BASE_SLOTS : TEACHER_BASE_SLOTS;
    }
}
