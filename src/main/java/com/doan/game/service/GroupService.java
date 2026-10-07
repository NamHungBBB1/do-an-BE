package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Vòng đời nhóm: mở nhóm gia đình (gói PARENT, 4 slot) hoặc lớp (gói TEACHER, 40 slot), mỗi tài khoản tối đa một nhóm đang mở cho mỗi loại; kết thúc nhóm; đếm chỗ trống; giáo viên xác nhận đã có đồng ý của phụ huynh.
 *
 * Bảng phụ trách: LearnerGroup
 */
public interface GroupService {

    LearnerGroupResponse openGroup(UUID ownerId, CreateGroupRequest req);

    /**
     * Các nhóm của NGƯỜI GỌI: nhóm đang mở đứng trước, nhóm đã đóng theo sau — FE chỉ gọi một
     * đường là thấy đủ. slotUsed đếm slot ACTIVE. 07/10 (Kidz chốt phạm vi PR 2).
     */
    java.util.List<LearnerGroupResponse> getMyGroups(UUID callerId);

    /** Slot trong một nhóm (mọi trạng thái để FE thấy cả ARCHIVED / WIPED), chỉ chủ nhóm (3004). */
    java.util.List<SlotResponse> listSlots(UUID callerId, UUID groupId);

    /**
     * Chủ nhóm CLASS xác nhận đã có đồng ý của phụ huynh (consentConfirmedAt = now) — bắt buộc
     * trước khi mở slot trong nhóm CLASS (5003); FAMILY không cần. 07/10, Hưng chốt.
     */
    void confirmConsent(UUID callerId, UUID groupId);

    void closeGroup(UUID groupId);

    /** Chỗ trống = slotLimit − số slot ACTIVE, chỉ chủ nhóm (3004). */
    int countFreeSlots(UUID callerId, UUID groupId);

}
