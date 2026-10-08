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
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Vòng đời nhóm: mở nhóm gia đình (gói PARENT, 4 slot) hoặc lớp (gói TEACHER, 40 slot), mỗi tài khoản tối đa một nhóm đang mở cho mỗi loại; kết thúc nhóm; đếm chỗ trống; giáo viên xác nhận đã có đồng ý của phụ huynh.
 *
 * CÓ RUỘT openGroup + confirmConsent (07/10, feat/group-slot), getMyGroups / listSlots /
 * countFreeSlots (PR 2a) và closeGroup (PR 2b).
 */
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    /** Hạn mức chép vào nhóm lúc mở (ERD): gia đình 4, lớp 40. */
    static final int FAMILY_SLOT_LIMIT = 4;
    static final int CLASS_SLOT_LIMIT = 40;

    private final LearnerGroupRepository groupRepo;
    private final LearnerSlotRepository slotRepo;
    private final AccountRepository accountRepo;
    private final EntitlementService entitlementService;
    private final Clock clock;

    /**
     * Mở nhóm. Thứ tự kiểm: name → context → gói còn hạn → ghi.
     *
     * Trùng (owner, openContext): hai request song song cùng vượt qua kiểm gói rồi cùng chèn.
     * Bắt DataIntegrityViolationException nhưng CHỈ đổi thành 5001 khi là khoá thật (23505) —
     * bài học 06/10: cột NOT NULL sót trong DB production từng bị báo nhầm thành lỗi nghiệp vụ.
     * saveAndFlush trong try để lỗi nổ ngay tại chỗ, không lẫn với lỗi của câu lệnh sau.
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
        PlanKind required = context == LearningContext.FAMILY ? PlanKind.PARENT : PlanKind.TEACHER;
        if (!entitlementService.activePlans(ownerId).contains(required)) {
            // Dùng lại 3005 PLAN_REQUIRED có sẵn (Hưng chốt 07/10) — hai mã cùng nghĩa thì FE
            // phải bắt cả hai, mà 3005 đúng nghĩa "cần mua gói trước" và chưa nơi nào dùng.
            throw new AppException(ErrorCode.PLAN_REQUIRED);
        }
        Account owner = accountRepo.findById(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có tài khoản " + ownerId));

        LearnerGroup group = new LearnerGroup();
        group.setOwner(owner);
        group.setContext(context);
        group.setOpenContext(context);
        group.setName(name.trim());
        group.setSlotLimit(context == LearningContext.FAMILY ? FAMILY_SLOT_LIMIT : CLASS_SLOT_LIMIT);
        group.setOpenedAt(Instant.now(clock));
        try {
            groupRepo.saveAndFlush(group);
        } catch (DataIntegrityViolationException e) {
            if (!DbErrors.isUniqueViolation(e)) {
                throw e;
            }
            throw new AppException(ErrorCode.GROUP_ALREADY_OPEN);
        }
        return LearnerGroupMapper.toResponse(group);
    }

    /**
     * Nhóm của người gọi: đang mở trước, đã đóng sau. slotUsed lấy từ MỘT câu đếm gộp
     * (countByStatusGroupedByGroup) thay vì N+1 từng nhóm.
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
        // Mới mở đến đóng: openContext = NULL nghĩa là đã đóng (UNIQUE(owner, openContext) ở entity).
        List<LearnerGroup> open = new ArrayList<>();
        List<LearnerGroup> closed = new ArrayList<>();
        for (LearnerGroup g : groups) {
            (g.getOpenContext() == null ? closed : open).add(g);
        }
        open.addAll(closed);
        List<LearnerGroupResponse> result = new ArrayList<>(open.size());
        for (LearnerGroup g : open) {
            result.add(LearnerGroupMapper.toResponse(g, used.getOrDefault(g.getId(), 0L).intValue()));
        }
        return result;
    }

    /**
     * Slot của một nhóm — MỌI trạng thái (cả ARCHIVED / WIPED) vì FE danh sách phải thấy được
     * lịch sử; locked tính theo đồng hồ của service, mapper không có đồng hồ.
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
     * Kết thúc nhóm. Đóng lần hai thì 5002 (đã đóng — ghi vào PR hỏi lại nếu Hưng muốn idempotent).
     *
     * ERD mục 4: "đông cứng báo cáo nhóm, lưu trữ nhóm và các slot, trả lại hạn mức, không xoá gì".
     * Báo cáo hiện là khung, nên ghi rõ trong PR: báo cáo dựng sau từ dữ liệu tới closedAt — sau
     * khi đóng, slot đã ARCHIVED nên dữ liệu không thay đổi nữa. openContext = NULL trả chỗ cho
     * UNIQUE(ownerId, openContext) để mở nhóm mới. Hưng chốt 07/10: không có API khôi phục.
     */
    @Override
    @Transactional
    public void closeGroup(UUID callerId, UUID groupId) {
        // lockById (FOR UPDATE) chứ không phải findById: openSlot/bulk đang chèn song song thì
        // closeGroup phải đứng sau nó — nếu không, hai bên commit song song và nhóm ĐÃ ĐÓNG vẫn
        // còn một slot ACTIVE (Kidz góp ý review PR 2b, 07/10).
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
        for (LearnerSlot slot : slotRepo.findByGroup_IdAndStatus(groupId, SlotStatus.ACTIVE)) {
            slot.setStatus(SlotStatus.ARCHIVED);
            slot.setArchivedAt(now);
            slotRepo.save(slot);
        }
    }

    /** Chỗ trống = hạn mức − slot ACTIVE; ARCHIVED / WIPED đã trả lại chỗ (ERD mục 5). */
    @Override
    @Transactional(readOnly = true)
    public int countFreeSlots(UUID callerId, UUID groupId) {
        LearnerGroup group = requireOwnedGroup(callerId, groupId);
        int limit = group.getSlotLimit() == null ? 0 : group.getSlotLimit();
        return limit - (int) slotRepo.countByGroup_IdAndStatus(groupId, SlotStatus.ACTIVE);
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
