package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Vòng đời nhóm: mở lớp, đóng lớp, đếm chỗ trống.
 *
 * Bảng phụ trách: LearnerGroup
 */
public interface GroupService {

    LearnerGroupResponse moNhom(UUID ownerId, CreateGroupRequest req);

    void dongNhom(UUID groupId);

    int demChoTrong(UUID groupId);

}
