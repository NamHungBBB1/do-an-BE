package com.doan.game.service.impl;

import com.doan.game.service.LearnerModelService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Mô hình người học: knowledge tracing từ mini-game và quiz, phân cụm hành vi (chỉ lớp), job tổng kết mỗi đêm gọi mô hình ngôn ngữ qua lớp AI provider; lỗi thì ghi bản theo luật (source = RULE). Chỉ gửi con số và mã slot, không gửi tên.
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
