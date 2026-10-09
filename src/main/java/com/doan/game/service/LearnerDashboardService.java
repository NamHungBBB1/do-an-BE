package com.doan.game.service;

import com.doan.game.DTO.response.LearnerDashboardResponse;

import java.util.UUID;

/**
 * Dashboard người học: GET /api/learner/dashboard, token SLOT — contract Hưng duyệt 08/10
 * (đề xuất của Triệu). Trẻ không có tham số: slot lấy từ sub của token, chỉ thấy dữ liệu của
 * chính mình.
 *
 * Đợt 1 chỉ dựng khối learner; các khối còn lại trả mặc định đúng bảng "Theo đợt" để FE dựng
 * giao diện một lần. Bảng phụ trách: LearnerSlot, LearnerGroup (đợt sau thêm tiến độ / quiz /
 * thành tích).
 */
public interface LearnerDashboardService {

    LearnerDashboardResponse getDashboard(UUID slotId);

}
