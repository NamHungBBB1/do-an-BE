package com.doan.game.service.impl;

import com.doan.game.service.GroupService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import com.doan.game.entity.Account;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.DbErrors;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.LearnerGroupMapper;
import com.doan.game.mapper.SlotMapper;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.service.EntitlementService;
import com.doan.game.service.ReportService;
import com.doan.game.service.SlotQuota;
import com.doan.game.service.SlotQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Vòng đời nhóm: mở nhóm gia đình (gói PARENT) hoặc lớp (gói TEACHER), kết thúc nhóm, đếm chỗ trống,
 * giáo viên xác nhận đã có đồng ý của phụ huynh.
 *
 * 17/11/2026 — đổi mô hình hạn mức (giảng viên chốt): hạn mức nằm trên TÀI KHOẢN (SlotQuotaService),
 * không chép 4/40 vào nhóm nữa. Mỗi tài khoản tối đa MỘT nhóm FAMILY đang mở; CLASS mở bao nhiêu lớp
 * cũng được, chỉ bị chặn bởi ví slot. Sĩ số lớp do giáo viên đặt (slot_limit).
 */
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    private final LearnerGroupRepository groupRepo;
    private final LearnerSlotRepository slotRepo;
    private final AccountRepository accountRepo;
    private final AccountRoleRepository accountRoleRepo;
    private final EntitlementService entitlementService;
    private final SlotQuotaService quotaService;
    private final ReportService reportService;
    private final Clock clock;

    /**
     * Mở nhóm. Thứ tự kiểm: name → context → gói còn hạn (3005) → còn nhóm FAMILY đang mở? (5001)
     * → ví slot còn lượt? (3006) → sĩ số lớp hợp lệ (1001 / 3006) → ghi.
     *
     * CLASS: openContext = NULL nên UNIQUE(owner_id, open_context) không chặn — đó là cách cho giáo
     * viên mở nhiều lớp mà KHÔNG phải đổi ràng buộc CSDL (H2 không có partial unique index; production
     * đang chạy ddl-auto=update nên bỏ một unique constraint sẽ không tự xoá dưới DB). FAMILY vẫn giữ
     * openContext = FAMILY để độc quyền một nhóm.
     *
     * Trùng (owner, openContext): hai request song song cùng vượt qua kiểm gói rồi cùng chèn. Bắt
     * DataIntegrityViolationException nhưng CHỈ đổi thành 5001 khi là khoá thật (23505) — bài học
     * 06/10: cột NOT NULL sót trong DB production từng bị báo nhầm thành lỗi nghiệp vụ.
     */
    @Override
    @Transactional
    public LearnerGroupResponse openGroup(UUID ownerId, CreateGroupRequest req) {
        String name = req == null ? null : req.name();
        if (name == null || name.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name: bắt buộc");
        }
        if (name.trim().length() > 80) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "name: tối đa 80 ký tự");
        }
        LearningContext context = parseContext(req.context());
        if (accountRoleRepo.isInternal(ownerId)) {
            throw new AppException(ErrorCode.INTERNAL_ACCOUNT_NOT_CUSTOMER);
        }
        PlanKind required = context == LearningContext.FAMILY ? PlanKind.PARENT : PlanKind.TEACHER;
        if (!entitlementService.activePlans(ownerId).contains(required)) {
            // Dùng lại 3005 PLAN_REQUIRED có sẵn (Hưng chốt 07/10) — hai mã cùng nghĩa thì FE
            // phải bắt cả hai, mà 3005 đúng nghĩa "cần mua gói trước" và chưa nơi nào dùng.
            throw new AppException(ErrorCode.PLAN_REQUIRED);
        }
        if (context == LearningContext.FAMILY
                && groupRepo.existsByOwner_IdAndContextAndClosedAtIsNull(ownerId, context)) {
            throw new AppException(ErrorCode.GROUP_ALREADY_OPEN);
        }

        SlotQuota quota = quotaService.quota(ownerId, context);
        Integer plannedSize = null;
        if (context == LearningContext.CLASS) {
            // 18/11/2026 (Q17): sĩ số BẮT BUỘC và phải giữ chỗ tối thiểu MIN_CLASS_SIZE trong ví —
            // lớp rỗng / lớp 1 em là cách lách hạn mức cũ, giờ chỗ giữ trừ thẳng vào free.
            Integer asked = req.plannedSize();
            if (asked == null) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "plannedSize: bắt buộc, ít nhất " + SlotQuotaService.MIN_CLASS_SIZE);
            }
            if (asked < SlotQuotaService.MIN_CLASS_SIZE) {
                throw new AppException(ErrorCode.VALIDATION_FAILED,
                        "plannedSize: ít nhất " + SlotQuotaService.MIN_CLASS_SIZE);
            }
            if (asked > quota.free()) {
                throw new AppException(ErrorCode.SLOT_LIMIT_REACHED);
            }
            plannedSize = asked;
        } else if (quota.free() <= 0) {
            // Nhà còn 0 chỗ thì mở nhóm cũng vô nghĩa vì không mở được slot nào.
            throw new AppException(ErrorCode.SLOT_LIMIT_REACHED);
        }

        Account owner = accountRepo.findById(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản " + ownerId));

        LearnerGroup group = new LearnerGroup();
        group.setOwner(owner);
        group.setContext(context);
        group.setOpenContext(context == LearningContext.FAMILY ? context : null);
        group.setName(name.trim());
        group.setSlotLimit(plannedSize);   // CLASS: sĩ số giáo viên đặt; FAMILY: NULL (ví quyết định)
        group.setOpenedAt(Instant.now(clock));
        try {
            groupRepo.saveAndFlush(group);
        } catch (DataIntegrityViolationException e) {
            if (!DbErrors.isUniqueViolation(e)) {
                throw e;
            }
            throw new AppException(ErrorCode.GROUP_ALREADY_OPEN);
        }
        return LearnerGroupMapper.toResponse(group, 0, displayLimit(context, quota, plannedSize));
    }

    /**
     * Nhóm của người gọi: đang mở trước, đã đóng sau. "Đang mở" = closedAt == null (17/11/2026: không
     * dùng openContext nữa vì CLASS đang mở cũng có openContext = NULL). slotUsed lấy từ MỘT câu đếm
     * gộp (countByStatusGroupedByGroup) thay vì N+1 từng nhóm; slotLimit hiệu lực tính một lần cho mỗi
     * loại nhóm.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LearnerGroupResponse> getMyGroups(UUID callerId) {
        Map<UUID, Long> used = new HashMap<>();
        for (LearnerSlotRepository.SlotUsedRow row
                : slotRepo.countByStatusGroupedByGroup(SlotStatus.ACTIVE, callerId)) {
            used.put(row.getGroupId(), row.getUsed());
        }
        List<LearnerGroup> groups = groupRepo.findByOwner_IdOrderByOpenedAtDesc(callerId);
        List<LearnerGroup> open = new ArrayList<>();
        List<LearnerGroup> closed = new ArrayList<>();
        for (LearnerGroup g : groups) {
            (g.getClosedAt() == null ? open : closed).add(g);
        }
        open.addAll(closed);

        Map<LearningContext, SlotQuota> quotas = new EnumMap<>(LearningContext.class);
        List<LearnerGroupResponse> result = new ArrayList<>(open.size());
        for (LearnerGroup g : open) {
            SlotQuota quota = quotas.computeIfAbsent(g.getContext(), c -> quotaService.quota(callerId, c));
            result.add(LearnerGroupMapper.toResponse(g, used.getOrDefault(g.getId(), 0L).intValue(),
                    displayLimit(g.getContext(), quota, g.getSlotLimit())));
        }
        return result;
    }

    /**
     * Slot của một nhóm — MỌI trạng thái (cả ARCHIVED / WIPED) vì FE danh sách phải thấy được lịch sử;
     * locked tính theo đồng hồ của service, mapper không có đồng hồ.
     */
    @Override
    @Transactional(readOnly = true)
    public List<SlotResponse> listSlots(UUID callerId, UUID groupId) {
        LearnerGroup group = requireOwnedGroup(callerId, groupId);
        Instant now = Instant.now(clock);
        List<SlotResponse> result = new ArrayList<>();
        for (LearnerSlot slot : slotRepo.findByGroup_IdOrderByCreatedAtAsc(group.getId())) {
            boolean locked = slot.getLockedUntil() != null && slot.getLockedUntil().isAfter(now);
            result.add(SlotMapper.toResponse(slot, locked));
        }
        return result;
    }

    /**
     * Chủ nhóm bấm "đã có đồng ý": ghi consentConfirmedAt = now. Idempotent — bấm lần hai vẫn
     * 200, giữ mốc lần đầu. Chỉ chủ nhóm mới bấm được (3004) — cùng một cửa với mở slot.
     */
    @Override
    @Transactional
    public void confirmConsent(UUID callerId, UUID groupId) {
        LearnerGroup group = requireOwnedGroup(callerId, groupId);
        if (group.getConsentConfirmedAt() == null) {
            group.setConsentConfirmedAt(Instant.now(clock));
            groupRepo.save(group);
        }
    }

    /**
     * Kết thúc nhóm. Đóng lần hai thì 5002. ERD mục 4: đông cứng báo cáo, lưu trữ nhóm và slot, không
     * xoá gì.
     *
     * 17/11/2026: đóng nhóm KHÔNG trả lượt slot. Slot đã mở là tiêu hao của kỳ (Q6) — đóng lớp rồi mở
     * lớp mới trong cùng kỳ vẫn bị trừ đúng số slot đã mở. openContext = NULL chỉ để mở được nhóm
     * FAMILY mới và để "đang mở" suy từ closedAt.
     *
     * Đông cứng (ReportService.freezeOnClose, 12/10) chạy TRƯỚC khi lưu trữ và CÙNG transaction: một
     * lỗi ở đóng băng thì cả closeGroup rollback; summary.active = số slot đang ACTIVE đúng lúc đóng.
     */
    @Override
    @Transactional
    public void closeGroup(UUID callerId, UUID groupId) {
        // lockById (FOR UPDATE) chứ không phải findById: openSlot/bulk đang chèn song song thì
        // closeGroup phải đứng sau nó — nếu không, nhóm ĐÃ ĐÓNG vẫn còn một slot ACTIVE (07/10).
        LearnerGroup group = groupRepo.lockById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có nhóm " + groupId));
        if (callerId == null || group.getOwner() == null || !callerId.equals(group.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (group.getClosedAt() != null) {
            throw new AppException(ErrorCode.GROUP_CLOSED);
        }
        Instant now = Instant.now(clock);
        group.setClosedAt(now);
        group.setOpenContext(null);
        groupRepo.save(group);
        reportService.freezeOnClose(groupId);
        slotRepo.archiveActiveSlots(groupId, now, SlotStatus.ACTIVE, SlotStatus.ARCHIVED);
    }

    /**
     * Chỗ còn mở được của MỘT nhóm. FAMILY: ví là trần duy nhất. CLASS: sĩ số còn trống (mọi slot đã
     * tạo trong lớp đều ăn chỗ, kể cả slot đã xoá — 18/11/2026), KHÔNG trừ `free` lần nữa vì chỗ này
     * đã nằm trong `reserved` của ví. Nhóm đã đóng → 0.
     */
    @Override
    @Transactional(readOnly = true)
    public int countFreeSlots(UUID callerId, UUID groupId) {
        LearnerGroup group = requireOwnedGroup(callerId, groupId);
        if (group.getClosedAt() != null) {
            return 0;
        }
        SlotQuota quota = quotaService.quota(callerId, group.getContext());
        if (group.getContext() != LearningContext.CLASS || group.getSlotLimit() == null) {
            return quota.free();
        }
        return Math.max(0, group.getSlotLimit() - (int) slotRepo.countByGroup_Id(groupId));
    }

    /**
     * Sửa sĩ số lớp (18/11/2026). Không nhỏ hơn số slot ĐÃ TẠO trong lớp (kể cả đã xoá — chỗ đã tiêu
     * hao), không nhỏ hơn MIN_CLASS_SIZE; phần tăng thêm phải còn `free` trong ví.
     */
    @Override
    @Transactional
    public LearnerGroupResponse updatePlannedSize(UUID callerId, UUID groupId, UpdateGroupRequest req) {
        LearnerGroup group = requireOwnedGroup(callerId, groupId);
        if (group.getContext() != LearningContext.CLASS) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "plannedSize: chỉ lớp CLASS mới có sĩ số");
        }
        if (group.getClosedAt() != null) {
            throw new AppException(ErrorCode.GROUP_CLOSED);
        }
        Integer planned = req == null ? null : req.plannedSize();
        if (planned == null || planned < SlotQuotaService.MIN_CLASS_SIZE) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "plannedSize: ít nhất " + SlotQuotaService.MIN_CLASS_SIZE);
        }
        int created = (int) slotRepo.countByGroup_Id(groupId);
        if (planned < created) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    "plannedSize: không nhỏ hơn số slot đã tạo trong lớp (" + created + ")");
        }
        int current = group.getSlotLimit() == null ? created : group.getSlotLimit();
        int growing = Math.max(0, planned - current);
        SlotQuota quota = quotaService.quota(callerId, LearningContext.CLASS);
        if (growing > quota.free()) {
            throw new AppException(ErrorCode.SLOT_LIMIT_REACHED);
        }
        group.setSlotLimit(planned);
        groupRepo.save(group);
        int active = (int) slotRepo.countByGroup_IdAndStatus(groupId, SlotStatus.ACTIVE);
        return LearnerGroupMapper.toResponse(group, active, planned);
    }

    /** Nhóm phải tồn tại (1003) và phải là của người gọi (3004) — một cửa cho mọi thao tác. */
    private LearnerGroup requireOwnedGroup(UUID callerId, UUID groupId) {
        LearnerGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có nhóm " + groupId));
        if (callerId == null || group.getOwner() == null || !callerId.equals(group.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return group;
    }

    /**
     * 16/10 (quiz.md v2): bật / tắt hậu trắc, idempotent. Chỉ chủ nhóm đang mở (đã đóng → 8008, không phải chủ →
     * 3004). 16/11 (v3): gia đình cũng bật được — BẬT cần gói đúng loại nhóm còn hạn (CLASS → TEACHER, FAMILY → PARENT,
     * thiếu → 3005), TẮT chỉ cần chủ nhóm — gói hết hạn vẫn tắt được. Giữ mốc bật lần đầu như confirmConsent.
     */
    @Override
    @Transactional
    public void setPostTest(UUID callerId, UUID groupId, boolean enabled) {
        LearnerGroup group = requireOwnedGroup(callerId, groupId);
        if (group.getClosedAt() != null) {
            throw new AppException(ErrorCode.GROUP_NOT_CLASS_OR_CLOSED, "Nhóm đã đóng");
        }
        if (enabled) {
            PlanKind needed = group.getContext() == LearningContext.FAMILY ? PlanKind.PARENT : PlanKind.TEACHER;
            if (!entitlementService.activePlans(callerId).contains(needed)) {
                throw new AppException(ErrorCode.PLAN_REQUIRED);
            }
            if (group.getPostTestEnabledAt() == null) {
                group.setPostTestEnabledAt(Instant.now(clock));
                groupRepo.save(group);
            }
        } else if (group.getPostTestEnabledAt() != null) {
            group.setPostTestEnabledAt(null);
            groupRepo.save(group);
        }
    }

    /**
     * slotLimit trả ra FE: FAMILY = trần ví (base + add-on) để mua thêm slot là con số tự lớn; CLASS =
     * sĩ số đã đặt, bỏ trống thì bằng trần ví.
     */
    private static int displayLimit(LearningContext context, SlotQuota quota, Integer plannedSize) {
        if (context == LearningContext.CLASS && plannedSize != null) {
            return plannedSize;
        }
        return quota.limit();
    }

    private static LearningContext parseContext(String context) {
        if (context == null || context.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "context: bắt buộc (FAMILY hoặc CLASS)");
        }
        try {
            return LearningContext.valueOf(context.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "context: chỉ FAMILY hoặc CLASS");
        }
    }

}
