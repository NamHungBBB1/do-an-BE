package com.doan.game.service;

import com.doan.game.enums.LearningContext;

import java.util.List;
import java.util.UUID;

/**
 * Nơi DUY NHẤT tính hạn mức slot. Hạn mức nằm trên TÀI KHOẢN (ví slot), nhóm chỉ khai sĩ số.
 *
 * 18/11/2026 — gói vĩnh viễn: bỏ mọi khái niệm kỳ / trần tổng. Lớp CLASS phải GIỮ CHỖ tối thiểu
 * {@link #MIN_CLASS_SIZE} slot, chỗ giữ trừ thẳng vào ví (chống mở lớp rỗng ôm chỗ).
 *
 * Bảng phụ trách: SlotAddon (phần cộng thêm), Entitlement (có gói), LearnerSlot (đã dùng),
 * LearnerGroup (chỗ đã giữ).
 */
public interface SlotQuotaService {

    /** Slot mặc định của gói Parent (chốt Q2). */
    int PARENT_BASE_SLOTS = 4;
    /** Slot mặc định của gói Teacher (chốt Q3). */
    int TEACHER_BASE_SLOTS = 40;
    /** Sĩ số tối thiểu của một lớp CLASS — lớp nhỏ hơn thì không mở được (chốt Q17). */
    int MIN_CLASS_SIZE = 5;

    /** Ví của tài khoản theo loại nhóm: FAMILY → gói PARENT, CLASS → gói TEACHER. */
    SlotQuota quota(UUID accountId, LearningContext context);

    /** Ví của mọi loại gói tài khoản đang có — GET /api/me/quota. */
    List<SlotQuota> quotas(UUID accountId);
}
