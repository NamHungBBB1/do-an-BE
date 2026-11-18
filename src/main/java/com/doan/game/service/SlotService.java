package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Slot của trẻ: AVAILABLE không có dòng, mở slot (tên + PIN) mới tạo dòng và sinh mã; mở nhiều slot một lần cho lớp; xoá ẩn danh, đổi tên / PIN; trẻ đăng nhập bằng mã + PIN, sai 5 lần khoá 15 phút (tính từ lockedUntil, không có job mở khoá).
 *
 * Bảng phụ trách: LearnerSlot
 */
public interface SlotService {

    /**
     * Mở một slot cho trẻ trong nhóm của NGƯỜI GỌI.
     *
     * 07/10 (Hưng chốt): chữ ký là (callerId, req) — groupId lấy từ req.groupId() (DTO đổi qua
     * PR #10, Hưng duyệt), callerId để service tự kiểm "nhóm là của mình" (3004), không tin vai
     * trong JWT. Controller lấy callerId từ JWT sub.
     */
    SlotResponse openSlot(UUID callerId, CreateSlotRequest req);

    /**
     * Mở nhiều slot một lần (lớp dán danh sách). Mọi phần tử phải cùng groupId; vượt hạn mức
     * thì TỪ CHỐI CẢ LÔ (3006); tất cả trong MỘT transaction, khoá dòng nhóm một lần.
     * Cùng thứ tự kiểm với openSlot. 07/10 (Hưng chốt phạm vi PR 2b).
     */
    java.util.List<SlotResponse> openSlots(UUID callerId, CreateSlotsRequest req);

    /**
     * Xoá ẩn danh (WIPED: bỏ tên, PIN, cá nhân hoá, badge; giữ mã + dòng + kết quả học — nhận cả
     * ACTIVE lẫn ARCHIVED, WIPED thì 5004), đổi tên + PIN (PATCH, chỉ slot ACTIVE, tên không trùng
     * slot ACTIVE khác trong nhóm), đổi PIN (vẫn ACTIVE, reset bộ đếm sai và thời khoá). Cả ba chỉ
     * chủ nhóm của slot (3004). 18/11/2026 (Q14/Q18).
     */
    void wipeSlot(UUID callerId, UUID slotId);

    SlotResponse renameSlot(UUID callerId, UUID slotId, UpdateSlotRequest req);

    void changePin(UUID callerId, UUID slotId, ChangePinRequest req);

    SlotLoginResponse loginSlot(String code, String pin);

}
