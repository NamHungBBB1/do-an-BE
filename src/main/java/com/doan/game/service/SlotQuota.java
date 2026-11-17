package com.doan.game.service;

import com.doan.game.enums.PlanKind;

import java.time.LocalDate;

/**
 * Ví slot của một tài khoản trong MỘT kỳ gói (chốt 17/11/2026).
 *
 * <pre>
 * limit     = base(gói) + extra(add-on còn hạn)
 * used      = số slot đã tạo trong kỳ này (mọi trạng thái — trả / xoá KHÔNG hoàn lượt)
 * remaining = limit − used, không âm
 * </pre>
 *
 * "Kỳ" là khoảng hiệu lực của gói đang chạy; sang kỳ mới used tự đếm lại từ 0 vì đếm theo
 * learner_slot.created_at >= termStart, không cần job reset.
 */
public record SlotQuota(PlanKind kind, int base, int extra, int used,
                        LocalDate termStart, LocalDate termEnd) {

    /** Tổng số slot được phép có trong kỳ (chưa trừ đã dùng). */
    public int limit() {
        return base + extra;
    }

    /** Số slot còn mở được. Không bao giờ âm dù dữ liệu cũ vượt hạn mức. */
    public int remaining() {
        return Math.max(0, limit() - used);
    }
}
