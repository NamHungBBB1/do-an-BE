package com.doan.game.service.impl;

import com.doan.game.service.GroupService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import com.doan.game.entity.Account;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.PlanKind;
import com.doan.game.exception.AppException;
import com.doan.game.exception.DbErrors;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.LearnerGroupMapper;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Vòng đời nhóm: mở nhóm gia đình (gói PARENT, 4 slot) hoặc lớp (gói TEACHER, 40 slot), mỗi tài khoản tối đa một nhóm đang mở cho mỗi loại; kết thúc nhóm; đếm chỗ trống; giáo viên xác nhận đã có đồng ý của phụ huynh.
 *
 * CÓ RUỘT openGroup (07/10, feat/group-slot). Ràng buộc UNIQUE(owner, openContext) nằm sẵn trên
 * entity: mỗi tài khoản tối đa 1 nhóm ĐANG MỞ cho mỗi context (openContext = context khi mở, NULL
 * khi đã đóng). CLASS đi chung một đường với FAMILY — chỉ khác gói cần và hạn mức; nếu sau này
 * giáo viên được mở nhiều lớp cùng lúc thì chỉ đổi ràng buộc ở ERD, code gần như giữ nguyên.
 *
 * Phần confirmConsent / closeGroup / countFreeSlots vẫn là KHUNG — ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    /** Hạn mức chép vào nhóm lúc mở (ERD): gia đình 4, lớp 40. */
    static final int FAMILY_SLOT_LIMIT = 4;
    static final int CLASS_SLOT_LIMIT = 40;

    private final LearnerGroupRepository groupRepo;
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
            // Dùng lại 3005 PLAN_REQUIRED có sẵn (Kidz chốt 07/10) — hai mã cùng nghĩa thì FE
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
     * Chủ nhóm bấm "đã có đồng ý": ghi consentConfirmedAt = now. Idempotent — bấm lần hai vẫn
     * 200, giữ mốc lần đầu. Chỉ chủ nhóm mới bấm được (3004) — cùng một cửa với mở slot.
     */
    @Override
    @Transactional
    public void confirmConsent(UUID callerId, UUID groupId) {
        LearnerGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có nhóm " + groupId));
        if (callerId == null || group.getOwner() == null || !callerId.equals(group.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (group.getConsentConfirmedAt() == null) {
            group.setConsentConfirmedAt(Instant.now(clock));
            groupRepo.save(group);
        }
    }

    @Override
    public void closeGroup(UUID groupId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public int countFreeSlots(UUID groupId) {
        throw new UnsupportedOperationException("chua cai dat");
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
