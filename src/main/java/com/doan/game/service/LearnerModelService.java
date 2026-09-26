package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Mô hình người học: knowledge tracing, phân cụm lớp, và job tổng kết mỗi đêm gọi mô hình ngôn ngữ bên thứ ba (FR-25 đến FR-27). Chỉ gửi con số đã tính và mã slot ra ngoài, không bao giờ gửi tên (BR-404).
 *
 * Bảng phụ trách: ConceptMastery, DailySummary
 */
public interface LearnerModelService {

    void capNhatMucNam(UUID slotId, int chapter);

    void phanCumLop(UUID groupId);

    void vietTongKetDem(java.time.LocalDate ngay);

    String xemTongKet(UUID slotId, java.time.LocalDate ngay);

}
