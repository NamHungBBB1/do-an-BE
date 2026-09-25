package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Đông cứng báo cáo lúc đóng nhóm và đọc lại báo cáo cũ.
 *
 * Bảng phụ trách: GroupReport, GroupReportRow
 */
public interface ReportService {

    GroupReportResponse dongCung(UUID groupId);

    GroupReportResponse xemBaoCao(UUID reportId);

}
