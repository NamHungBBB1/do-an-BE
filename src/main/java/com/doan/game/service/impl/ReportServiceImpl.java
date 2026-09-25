package com.doan.game.service.impl;

import com.doan.game.service.ReportService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Đông cứng báo cáo lúc đóng nhóm và đọc lại báo cáo cũ.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    @Override
    public GroupReportResponse dongCung(UUID groupId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public GroupReportResponse xemBaoCao(UUID reportId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
