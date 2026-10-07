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
import com.doan.game.exception.ErrorCode;
import com.doan.game.mapper.SlotMapper;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.service.SlotService;
import com.doan.game.service.TokenService;
import lombok.RequiredArgsConstructor;
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
 * openSlot (07/10, feat/group-slot) và returnSlot / wipeSlot / changePin (PR 2a). Phần còn lại
 * vẫn là KHUNG — openSlots (bulk, PR 2b) ném UnsupportedOperationException để không ai vô tình
 * dùng một lớp rỗng mà tưởng nó chạy.
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
     * Thứ tự kiểm (Hưng chốt 07/10): nhóm tồn tại (1003) → nhóm là của người gọi (3004)
     * → nhóm chưa đóng (5002) → nhóm CLASS đã có consent (5003, FAMILY không cần) →
     * tên / PIN hợp lệ (1001) → còn chỗ (3006).
     *
     * KHÔNG kiểm gói ở đây: mở nhóm đã kiểm rồi (openGroup), còn "gói hết hạn có được mở slot
     * mới không" là câu hỏi để Hưng chốt — giữ nguyên, ghi vào PR chờ chốt.
     */
    @Override
    @Transactional
    public SlotResponse openSlot(UUID callerId, CreateSlotRequest req) {
        if (req == null || req.groupId() == null) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "groupId: bắt buộc");
        }
        UUID groupId = req.groupId();
        // lockById = khóa dòng nhóm (FOR UPDATE) trước khi đếm: đếm-cho-roi-chen không khóa thì
        // hai request song song cùng đếm 3/4 rồi cùng chèn, nhóm thành 5/4 (Kidz góp ý review
        // 07/10, mẫu TransactionRepository.lockByOrderCode).
        LearnerGroup group = groupRepo.lockById(groupId)
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
        slot.setCode(nextFreeCode());
        slotRepo.saveAndFlush(slot);
        return SlotMapper.toResponse(slot, false);
    }

    /**
     * Sinh mã chưa có ai dùng — kiểm existsByCode TRƯỚC, chèn MỘT lần sau đó.
     *
     * Không được bắt lỗi chèn trong vòng lặp: trong @Transactional, saveAndFlush ném
     * DataIntegrityViolation thì session Hibernate đã hỏng và transaction bị đánh dấu
     * rollback-only — thử lại trong cùng transaction không cứu được, cuối cùng ra
     * UnexpectedRollbackException (500). Kiểm rồi mới chèn chỉ để lại khe hai request cùng lúc
     * chọn trùng một mã (31^8 gần như không xảy ra) — nếu trúng thì để 500, không vì xác suất
     * 1e-11 mà khoá bảng.
     */
    private String nextFreeCode() {
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (!slotRepo.existsByCode(code)) {
                return code;
            }
        }
        throw new AppException(ErrorCode.UNCATEGORIZED,
                "sinh mã slot thất bại sau " + MAX_CODE_ATTEMPTS + " lần — thử lại");
    }

    @Override
    public List<SlotResponse> openSlots(UUID groupId, CreateSlotsRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    /**
     * Trả slot: ACTIVE → ARCHIVED, ghi archivedAt. Giữ nguyên tên và mã — chỉ là trạng thái đổi,
     * trẻ dùng lại mã thì loginSlot trả 3009 (đã có từ trước). Trả lại chỗ cho hạn mức vì
     * đếm slot đang làm trên ACTIVE (ERD mục 5).
     */
    @Override
    @Transactional
    public void returnSlot(UUID callerId, UUID slotId) {
        LearnerSlot slot = requireOwnedActive(callerId, slotId);
        slot.setStatus(SlotStatus.ARCHIVED);
        slot.setArchivedAt(Instant.now(clock));
        slotRepo.save(slot);
    }

    /**
     * Xoá sạch: WIPED, bỏ tên, mã và PIN — nhưng GIỮ dòng cho báo cáo cũ (ERD mục 5). Mã về NULL
     * nên trẻ không còn login được (findByCode không ra → 3007). Badge giữ lại: đó là cột phân
     * nhóm / báo cáo, không phải dữ liệu định danh của trẻ.
     */
    @Override
    @Transactional
    public void wipeSlot(UUID callerId, UUID slotId) {
        LearnerSlot slot = requireOwnedActive(callerId, slotId);
        slot.setStatus(SlotStatus.WIPED);
        slot.setWipedAt(Instant.now(clock));
        slot.setCode(null);
        slot.setPinHash(null);
        slot.setDisplayName(null);
        slotRepo.save(slot);
    }

    /**
     * Đổi PIN: vẫn ACTIVE (ERD mục 5). Reset failedAttempts + lockedUntil — đổi PIN là hành động
     * của người lớn tin cậy, gỡ luôn thời khoá đang treo cho trẻ.
     */
    @Override
    @Transactional
    public void changePin(UUID callerId, UUID slotId, ChangePinRequest req) {
        LearnerSlot slot = requireOwnedActive(callerId, slotId);
        String pin = req == null ? null : req.pin();
        if (pin == null || !pin.matches("\\d{6}")) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "pin: PIN gồm 6 chữ số");
        }
        slot.setPinHash(passwordEncoder.encode(pin));
        slot.setFailedAttempts(0);
        slot.setLockedUntil(null);
        slotRepo.save(slot);
    }

    /**
     * Cửa chung cho trả / xoá / đổi PIN: slot phải có (3007) → là của người gọi (3004,
     * kiểm qua chủ nhóm) → còn ACTIVE (5004). Thứ tự này cố ý: người khác không được biết
     * slot của người khác có tồn tại hay đang ở trạng thái gì.
     */
    private LearnerSlot requireOwnedActive(UUID callerId, UUID slotId) {
        LearnerSlot slot = slotRepo.findById(slotId)
                .orElseThrow(() -> new AppException(ErrorCode.SLOT_NOT_FOUND));
        LearnerGroup group = slot.getGroup();
        if (callerId == null || group.getOwner() == null || !callerId.equals(group.getOwner().getId())) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (slot.getStatus() != SlotStatus.ACTIVE) {
            throw new AppException(ErrorCode.SLOT_NOT_ACTIVE);
        }
        return slot;
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
