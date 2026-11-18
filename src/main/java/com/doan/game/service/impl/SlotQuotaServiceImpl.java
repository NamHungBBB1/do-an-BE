package com.doan.game.service.impl;

import com.doan.game.enums.LearningContext;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.SlotAddonRepository;
import com.doan.game.service.SlotQuota;
import com.doan.game.service.SlotQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tính ví slot: base theo gói + add-on đã mua − slot đã tạo − chỗ đã giữ cho lớp (18/11/2026).
 *
 * Gói vĩnh viễn (Q5): KHÔNG có mốc kỳ, không job reset. `used` đếm mọi slot từng tạo, mọi trạng thái
 * (Q6) — đó là lý do các câu đếm dưới đây không lọc status và không truyền `since`.
 *
 * `reserved` chỉ tính trên lớp CLASS đang mở: max(0, plannedSize − createdInClass). Đóng lớp thì câu
 * truy vấn tự bỏ lớp đó → chỗ chưa dùng quay về `free` mà không cần UPDATE gì.
 */
@Service
@RequiredArgsConstructor
public class SlotQuotaServiceImpl implements SlotQuotaService {

    private final EntitlementRepository entitlementRepo;
    private final SlotAddonRepository addonRepo;
    private final LearnerSlotRepository slotRepo;
    private final LearnerGroupRepository groupRepo;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public SlotQuota quota(UUID accountId, LearningContext context) {
        PlanKind kind = kindOf(context);
        Set<PlanKind> active = entitlementRepo.findActivePlanKinds(accountId, LocalDate.now(clock));
        if (!active.contains(kind)) {
            // Không có gói đang chạy: ví rỗng. Nơi gọi đã chặn 3005 trước đó, đây là lưới an toàn.
            return new SlotQuota(kind, 0, 0, 0, 0, 0);
        }
        int extra = (int) addonRepo.sumSlots(accountId, kind);
        int used = (int) slotRepo.countCreatedByOwnerAndContext(accountId, context);
        int reserved = reservedOf(accountId, context);
        int wiped = (int) slotRepo.countByOwnerAndContextAndStatus(accountId, context, SlotStatus.WIPED);
        return new SlotQuota(kind, baseOf(kind), extra, used, reserved, wiped);
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

    /** Chỗ đã giữ của các lớp CLASS đang mở; FAMILY không có sĩ số nên luôn 0. */
    private int reservedOf(UUID accountId, LearningContext context) {
        if (context != LearningContext.CLASS) {
            return 0;
        }
        int reserved = 0;
        for (LearnerGroupRepository.GroupCapacityRow row : groupRepo.openCapacity(accountId, context)) {
            int planned = row.getPlannedSize() == null ? 0 : row.getPlannedSize();
            reserved += Math.max(0, planned - (int) row.getCreated());
        }
        return reserved;
    }

    static PlanKind kindOf(LearningContext context) {
        return context == LearningContext.FAMILY ? PlanKind.PARENT : PlanKind.TEACHER;
    }

    static int baseOf(PlanKind kind) {
        return kind == PlanKind.PARENT ? PARENT_BASE_SLOTS : TEACHER_BASE_SLOTS;
    }
}
