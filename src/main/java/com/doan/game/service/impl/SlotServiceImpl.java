package com.doan.game.service.impl;

import com.doan.game.service.SlotService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Slot của trẻ: AVAILABLE không có dòng, mở slot (tên + PIN) mới tạo dòng và sinh mã; mở nhiều slot một lần cho lớp; trả, xoá sạch, đổi PIN; trẻ đăng nhập bằng mã + PIN, sai 5 lần khoá 15 phút (tính từ lockedUntil, không có job mở khoá).
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    @Override
    public SlotResponse moSlot(UUID groupId, CreateSlotRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public java.util.List<SlotResponse> moNhieuSlot(UUID groupId, CreateSlotsRequest req) {
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

    @Override
    public TokenResponse dangNhapTre(String code, String pin) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
