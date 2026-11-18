package com.doan.game.service;

import com.doan.game.enums.PlanKind;

/**
 * Ví slot của một tài khoản (chốt 18/11/2026 — gói vĩnh viễn, không reset theo kỳ).
 *
 * <pre>
 * limit     = base(gói) + extra(add-on đã mua)
 * used      = số slot đã tạo của tài khoản (mọi trạng thái — trả / xoá KHÔNG hoàn lượt)
 * reserved  = chỗ đã giữ cho các lớp CLASS đang mở: max(0, plannedSize − createdInClass)
 * free      = max(0, limit − used − reserved)
 * </pre>
 *
 * "Vĩnh viễn" = không có mốc kỳ: `used` đếm toàn bộ slot từng tạo, không lọc theo ngày. Chỗ đã giữ
 * của lớp chỉ trả về `free` khi lớp ĐÓNG (chỗ chưa dùng), còn slot đã tạo thì ở lại `used` mãi.
 *
 * `wiped` chỉ để hiển thị ("đã xoá bao nhiêu"), không tham gia phép tính.
 */
public record SlotQuota(PlanKind kind, int base, int extra, int used, int reserved, int wiped) {

    /** Tổng slot tài khoản sở hữu (chưa trừ đã dùng / đã giữ). */
    public int limit() {
        return base + extra;
    }

    /** Số slot còn dùng được ngay (mở slot FAMILY, mở lớp mới, tăng sĩ số). Không bao giờ âm. */
    public int free() {
        return Math.max(0, limit() - used - reserved);
    }
}
