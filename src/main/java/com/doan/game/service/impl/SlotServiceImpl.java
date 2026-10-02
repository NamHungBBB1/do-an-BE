package com.doan.game.service.impl;

import com.doan.game.DTO.request.ChangePinRequest;
import com.doan.game.DTO.request.CreateSlotRequest;
import com.doan.game.DTO.request.CreateSlotsRequest;
import com.doan.game.DTO.response.SlotResponse;
import com.doan.game.DTO.response.TokenResponse;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.SlotStatus;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.service.SlotService;
import com.doan.game.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Slot của trẻ: AVAILABLE không có dòng, mở slot (tên + PIN) mới tạo dòng và sinh mã; mở nhiều slot một lần cho lớp; trả, xoá sạch, đổi PIN; trẻ đăng nhập bằng mã + PIN, sai 5 lần khoá 15 phút (tính từ lockedUntil, không có job mở khoá).
 *
 * CÓ RUỘT phần dangNhapTre (02/10, làm cùng đợt AuthService) vì nó phát token qua cùng
 * TokenService. Phần còn lại vẫn là KHUNG — mọi hàm chưa làm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    /** PIN gốc 6 số là 1 triệu tổ hợp. Không khoá thì dò hết trong một buổi. */
    private static final int LAN_SAI_PIN_TOI_DA = 5;
    private static final Duration KHOA_SAI_PIN = Duration.ofMinutes(15);

    private final LearnerSlotRepository slotRepo;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    public SlotResponse moSlot(UUID groupId, CreateSlotRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public List<SlotResponse> moNhieuSlot(UUID groupId, CreateSlotsRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void traSlot(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void xoaSach(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void doiPin(UUID slotId, ChangePinRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    /**
     * Trẻ vào bằng mã in trên giấy + PIN 6 số.
     *
     * CỐ Ý KHÔNG @Transactional, y hệt dangNhap: sai PIN thì phải tăng bộ đếm rồi NÉM lỗi, mà ném
     * lỗi trong transaction là rollback — bộ đếm bị xoá và khoá chống dò không bao giờ đóng. Đây
     * là bẫy đã có test ở commit a982abc; học theo nguyên văn lần này.
     */
    @Override
    public TokenResponse dangNhapTre(String code, String pin) {
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
            int lan = (s.getFailedAttempts() == null ? 0 : s.getFailedAttempts()) + 1;
            if (lan >= LAN_SAI_PIN_TOI_DA) {
                s.setFailedAttempts(0);
                s.setLockedUntil(now.plus(KHOA_SAI_PIN));
            } else {
                s.setFailedAttempts(lan);
            }
            slotRepo.save(s);
            throw new AppException(ErrorCode.BAD_CREDENTIALS);
        }

        s.setFailedAttempts(0);
        s.setLockedUntil(null);
        slotRepo.save(s);
        return tokenService.choTre(s.getId());
    }

}
