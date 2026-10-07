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

    /**
     * Mở một slot cho trẻ trong nhóm của NGƯỜI GỌI.
     *
     * 07/10 (Kidz chốt): chữ ký là (callerId, req) — groupId lấy từ req.groupId() (DTO đổi qua
     * PR #10, Hưng duyệt), callerId để service tự kiểm "nhóm là của mình" (3004), không tin vai
     * trong JWT. Controller lấy callerId từ JWT sub.
     */
    SlotResponse openSlot(UUID callerId, CreateSlotRequest req);

    java.util.List<SlotResponse> openSlots(UUID groupId, CreateSlotsRequest req);

    void returnSlot(UUID slotId);

    void wipeSlot(UUID slotId);

    void changePin(UUID slotId, ChangePinRequest req);

    TokenResponse loginSlot(String code, String pin);

}
