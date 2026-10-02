package com.doan.game.service.impl;

import com.doan.game.service.GroupService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Vòng đời nhóm: mở nhóm gia đình (gói PARENT, 4 slot) hoặc lớp (gói TEACHER, 40 slot), mỗi tài khoản tối đa một nhóm đang mở cho mỗi loại; kết thúc nhóm; đếm chỗ trống; giáo viên xác nhận đã có đồng ý của phụ huynh.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    @Override
    public LearnerGroupResponse moNhom(UUID ownerId, CreateGroupRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void xacNhanDongY(UUID groupId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void dongNhom(UUID groupId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public int demChoTrong(UUID groupId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
