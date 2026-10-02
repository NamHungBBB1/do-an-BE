package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Slot của trẻ: AVAILABLE không có dòng, mở slot (tên + PIN) mới tạo dòng và sinh mã; mở nhiều slot một lần cho lớp; trả, xoá sạch, đổi PIN; trẻ đăng nhập bằng mã + PIN, sai 5 lần khoá 15 phút (tính từ lockedUntil, không có job mở khoá).
 *
 * Bảng phụ trách: LearnerSlot
 */
public interface SlotService {

    SlotResponse moSlot(UUID groupId, CreateSlotRequest req);

    java.util.List<SlotResponse> moNhieuSlot(UUID groupId, CreateSlotsRequest req);

    void traSlot(UUID slotId);

    void xoaSach(UUID slotId);

    void doiPin(UUID slotId, ChangePinRequest req);

    TokenResponse dangNhapTre(String code, String pin);

}
