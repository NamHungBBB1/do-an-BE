package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Đông cứng báo cáo lúc kết thúc nhóm và đọc lại báo cáo cũ.
 *
 * Bảng phụ trách: GroupReport, GroupReportRow
 */
public interface ReportService {

    GroupReportResponse freeze(UUID groupId);

    GroupReportResponse getReport(UUID reportId);

}
