package com.doan.game.service.impl;

import com.doan.game.service.LearnerModelService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Mô hình người học: knowledge tracing, phân cụm lớp, và job tổng kết mỗi đêm gọi mô hình ngôn ngữ bên thứ ba (FR-25 đến FR-27). Chỉ gửi con số đã tính và mã slot ra ngoài, không bao giờ gửi tên (BR-404).
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class LearnerModelServiceImpl implements LearnerModelService {

    @Override
    public void capNhatMucNam(UUID slotId, int chapter) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void phanCumLop(UUID groupId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void vietTongKetDem(java.time.LocalDate ngay) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public String xemTongKet(UUID slotId, java.time.LocalDate ngay) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
