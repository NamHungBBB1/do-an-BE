package com.doan.game.DTO.response;

import java.util.List;

/**
 * Trang tổng quan quản trị: số tài khoản, số slot của trẻ, doanh thu, tình trạng giao dịch, gói và
 * 10 thao tác cấp / thu vai mới nhất. Nhật ký thao tác admin chung chưa có bảng nên đợt 1 chỉ có sổ vai.
 */
public record AdminDashboardResponse(Accounts accounts, Learners learners, Revenue revenue,
                                     Transactions transactions, Plans plans,
                                     List<RoleGrantLogResponse> recentAdminActions) {

    /** internal = tài khoản giữ ít nhất một vai nội bộ; parents / teachers = có gói đang chạy. */
    public record Accounts(long total, long parents, long teachers, long internal) {
    }

    public record Learners(long active, long archived, long wiped) {
    }

    /** paidTotal = mọi đơn PAID; paidLast30Days = đơn PAID có paidAt trong 30 ngày gần nhất. */
    public record Revenue(long paidTotal, long paidLast30Days) {
    }

    public record Transactions(long pending, long failed, long needsReview) {
    }

    /** active = dòng gói đang chạy; expiringIn7Days = hết hạn trong 7 ngày tới. */
    public record Plans(long active, long expiringIn7Days) {
    }
}
