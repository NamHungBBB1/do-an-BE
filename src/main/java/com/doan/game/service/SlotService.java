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
     * 07/10 (Hưng chốt): chữ ký là (callerId, req) — groupId lấy từ req.groupId() (DTO đổi qua
     * PR #10, Hưng duyệt), callerId để service tự kiểm "nhóm là của mình" (3004), không tin vai
     * trong JWT. Controller lấy callerId từ JWT sub.
     */
    SlotResponse openSlot(UUID callerId, CreateSlotRequest req);

    /**
     * Mở nhiều slot một lần (lớp dán danh sách). Mọi phần tử phải cùng groupId; vượt hạn mức
     * thì TỪ CHỐI CẢ LÔ (3006); tất cả trong MỘT transaction, khoá dòng nhóm một lần.
     * Cùng thứ tự kiểm với openSlot. 07/10 (Kidz chốt phạm vi PR 2b).
     */
    java.util.List<SlotResponse> openSlots(UUID callerId, CreateSlotsRequest req);

    /**
     * Trả slot (ACTIVE → ARCHIVED, giữ tên và mã nhưng mã hết hiệu lực — 3009 khi trẻ dùng lại),
     * xoá sạch (WIPED: bỏ tên, mã, PIN, giữ dòng cho báo cáo cũ — nhận cả ACTIVE lẫn ARCHIVED,
     * WIPED thì 5004 — Hưng chốt 07/10), đổi PIN (vẫn ACTIVE, reset bộ đếm sai và thời khoá).
     * Cả ba chỉ chủ nhóm của slot (3004). 07/10 (Kidz chốt phạm vi PR 2).
     */
    void returnSlot(UUID callerId, UUID slotId);

    void wipeSlot(UUID callerId, UUID slotId);

    void changePin(UUID callerId, UUID slotId, ChangePinRequest req);

    TokenResponse loginSlot(String code, String pin);

}
