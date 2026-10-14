package com.doan.game.service;

import com.doan.game.DTO.response.GroupReportResponse;
import com.doan.game.DTO.response.SlotReportResponse;

import java.util.UUID;

/**
 * Báo cáo người lớn (Hưng duyệt 12/10, build/api-docs/report.md): đọc mọi thứ người lớn được xem
 * về học sinh / nhóm. Người gọi phải là CHỦ NHÓM — service tự kiểm, không tin vai trong JWT.
 *
 * Bảng phụ trách: chỉ ĐỌC RunState / ChoiceEvent / MiniGameResult / Achievement (Play đã ghi)
 * cộng ĐÔNG BĂNG vào GroupReport / GroupReportRow đúng lúc nhóm đóng. Không thêm bảng, không thêm cột.
 */
public interface ReportService {

    /**
     * Đông cứng báo cáo nhóm, gọi từ GroupServiceImpl.closeGroup CÙNG transaction, TRƯỚC khi
     * archiveActiveSlots — summary.active = số slot đang ACTIVE đúng lúc đóng, không phải 0.
     * Không có tham số quyền: closeGroup đã kiểm chủ nhóm trước khi gọi tới đây.
     */
    void freezeOnClose(UUID groupId);

    /** GET /api/groups/{id}/report: nhóm ĐÃ ĐÓNG lấy bản đóng băng, nhóm đang mở tính sống. */
    GroupReportResponse groupReport(UUID callerId, UUID groupId);

    /** GET /api/slots/{id}/report: một học sinh, chỉ chủ nhóm; token SLOT bị chặn từ cửa Security. */
    SlotReportResponse slotReport(UUID callerId, UUID slotId);
}
