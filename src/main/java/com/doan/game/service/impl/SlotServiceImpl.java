package com.doan.game.service.impl;

import com.doan.game.DTO.request.ChangePinRequest;
import com.doan.game.DTO.request.CreateSlotRequest;
import com.doan.game.DTO.request.CreateSlotsRequest;
import com.doan.game.DTO.response.SlotResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.DbErrors;
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.SlotMapper;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.service.SlotService;
import com.doan.game.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Slot của trẻ: AVAILABLE không có dòng, mở slot (tên + PIN) mới tạo dòng và sinh mã; mở nhiều slot một lần cho lớp; trả, xoá sạch, đổi PIN; trẻ đăng nhập bằng mã + PIN, sai 5 lần khoá 15 phút (tính từ lockedUntil, không có job mở khoá).
 *
 * CÓ RUỘT loginSlot (02/10, làm cùng đợt AuthService) vì nó phát token qua cùng TokenService,
 * và openSlot (07/10, feat/group-slot). Phần còn lại vẫn là KHUNG — mọi hàm chưa làm còn ném
 * UnsupportedOperationException để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    /** PIN gốc 6 số là 1 triệu tổ hợp. Không khoá thì dò hết trong một buổi. */
    private static final int MAX_PIN_ATTEMPTS = 5;
    private static final Duration PIN_LOCK_DURATION = Duration.ofMinutes(15);

    /**
     * Bảng chữ sinh mã slot: bỏ ký tự dễ nhầm (0, O, 1, I, L). 31 ký tự ^ 8 vị trí ≈ 8.5e11 tổ
     * hợp — trùng khoá là may rủi siêu hiếm, nhưng vẫn thử lại tối đa 5 lần rồi mới báo lỗi.
     */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    private final LearnerSlotRepository slotRepo;
    private final LearnerGroupRepository groupRepo;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * Người lớn mở một slot: tên + PIN 6 số; mã chữ do BE sinh (trẻ gõ mã + PIN để vào).
     *
     * Thứ tự kiểm (Kidz Bridge chốt 07/10): nhóm tồn tại (1003) → nhóm là của người gọi (3004)
     * → nhóm chưa đóng (5002) → nhóm CLASS đã có consent (5003, FAMILY không cần) →
     * tên / PIN hợp lệ (1001) → còn chỗ (3006).
     */
    @Override
    @Transactional
    public SlotResponse openSlot(UUID callerId, CreateSlotRequest req) {
        if (req == null || req.groupId() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "groupId: bắt buộc");
        }
        UUID groupId = req.groupId();
        LearnerGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "không có nhóm " + groupId));
        if (callerId == null || group.getOwner() == null || !callerId.equals(group.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (group.getClosedAt() != null) {
            throw new AppException(ErrorCode.GROUP_CLOSED);
        }
        // Hưng chốt 07/10: lớp phải có xác nhận đồng ý của phụ huynh trước khi có slot.
        if (group.getContext() == LearningContext.CLASS && group.getConsentConfirmedAt() == null) {
            throw new AppException(ErrorCode.GROUP_CONSENT_REQUIRED);
        }

        String displayName = req.displayName();
        if (displayName == null || displayName.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "displayName: bắt buộc");
        }
        if (displayName.trim().length() > 40) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "displayName: tối đa 40 ký tự");
        }
        String pin = req.pin();
        if (pin == null || !pin.matches("\\d{6}")) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "pin: PIN gồm 6 chữ số");
        }
        String badge = req.badge();
        if (badge != null && badge.length() > 40) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "badge: tối đa 40 ký tự");
        }

        int slotLimit = group.getSlotLimit() == null ? 0 : group.getSlotLimit();
        if (slotRepo.countByGroup_IdAndStatus(groupId, SlotStatus.ACTIVE) >= slotLimit) {
            throw new AppException(ErrorCode.SLOT_LIMIT_REACHED);
        }

        LearnerSlot slot = new LearnerSlot();
        slot.setGroup(group);
        slot.setPinHash(passwordEncoder.encode(pin));
        slot.setDisplayName(displayName.trim());
        slot.setBadge(badge == null ? null : badge.trim());
        slot.setStatus(SlotStatus.ACTIVE);
        slot.setFailedAttempts(0);
        slot.setCreatedAt(Instant.now(clock));
        // Trùng mã là may rủi siêu hiếm; nếu có thì thử mã khác, không phải lỗi nghiệp vụ nào.
        for (int attempt = 1; ; attempt++) {
            slot.setCode(randomCode());
            try {
                slotRepo.saveAndFlush(slot);
                break;
            } catch (DataIntegrityViolationException e) {
                if (!DbErrors.isUniqueViolation(e)) {
                    throw e;
                }
                if (attempt >= MAX_CODE_ATTEMPTS) {
                    throw new AppException(ErrorCode.UNCATEGORIZED,
                            "sinh mã slot thất bại sau " + MAX_CODE_ATTEMPTS + " lần — thử lại");
                }
            }
        }
        return SlotMapper.toResponse(slot, false);
    }

    @Override
    public List<SlotResponse> openSlots(UUID groupId, CreateSlotsRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void returnSlot(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void wipeSlot(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void changePin(UUID slotId, ChangePinRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    /**
     * Trẻ vào bằng mã in trên giấy + PIN 6 số.
     *
     * CỐ Ý KHÔNG @Transactional, y hệt login: sai PIN thì phải tăng bộ đếm rồi NÉM lỗi, mà ném
     * lỗi trong transaction là rollback — bộ đếm bị xoá và khoá chống dò không bao giờ đóng. Đây
     * là bẫy đã có test ở commit a982abc; học theo nguyên văn lần này.
     */
    @Override
    public TokenResponse loginSlot(String code, String pin) {
        if (code == null || code.isBlank() || pin == null || pin.isBlank()) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }
        LearnerSlot s = slotRepo.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));

        Instant now = Instant.now(clock);
        // WIPED không phải ARCHIVED: dữ liệu cũ còn giữ cho báo cáo nhưng mã này không còn là của ai,
        // nên trả NOT_FOUND chứ không phải SLOT_ARCHIVED ("lớp đã kết thúc" — không đúng chuyện đã xảy ra).
        if (s.getStatus() == SlotStatus.WIPED) {
            throw new AppException(ErrorCode.SLOT_NOT_FOUND);
        }
        if (s.getStatus() == SlotStatus.ARCHIVED) {
            throw new AppException(ErrorCode.SLOT_ARCHIVED);
        }
        // "Đang khoá" = lockedUntil > now. Không có cột LOCKED và không có job mở khoá, nên không
        // có cách nào để cờ và đồng hồ lệch nhau.
        if (s.getLockedUntil() != null && s.getLockedUntil().isAfter(now)) {
            throw new AppException(ErrorCode.SLOT_LOCKED);
        }

        if (!passwordEncoder.matches(pin, s.getPinHash())) {
            int attempts = (s.getFailedAttempts() == null ? 0 : s.getFailedAttempts()) + 1;
            if (attempts >= MAX_PIN_ATTEMPTS) {
                s.setFailedAttempts(0);
                s.setLockedUntil(now.plus(PIN_LOCK_DURATION));
            } else {
                s.setFailedAttempts(attempts);
            }
            slotRepo.save(s);
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }

        s.setFailedAttempts(0);
        s.setLockedUntil(null);
        slotRepo.save(s);
        return tokenService.issueForSlot(s.getId());
    }

    /** 8 ký tự HOA từ bảng chữ bỏ ký tự dễ nhầm — trẻ gõ tay nên từng chữ phải đọc được. */
    private static String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(CODE_RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

}
