package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Mô hình người học: knowledge tracing từ mini-game và quiz, phân cụm hành vi (chỉ lớp), job tổng kết mỗi đêm gọi mô hình ngôn ngữ qua lớp AI provider; lỗi thì ghi bản theo luật (source = RULE). Chỉ gửi con số và mã slot, không gửi tên.
 *
 * Bảng phụ trách: ConceptMastery, DailySummary
 */
public interface LearnerModelService {

    void capNhatMucNam(UUID slotId, int chapter);

    void phanCumLop(UUID groupId);

    void vietTongKetDem(java.time.LocalDate ngay);

    String xemTongKet(UUID slotId, java.time.LocalDate ngay);

}
