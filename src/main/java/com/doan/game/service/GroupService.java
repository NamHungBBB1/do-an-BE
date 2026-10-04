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

    void confirmConsent(UUID groupId);

    void closeGroup(UUID groupId);

    int countFreeSlots(UUID groupId);

}
