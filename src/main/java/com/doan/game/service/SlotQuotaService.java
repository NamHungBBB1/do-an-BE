package com.doan.game.service;

import com.doan.game.enums.LearningContext;

import java.util.List;
import java.util.UUID;

/**
 * Nơi DUY NHẤT tính hạn mức slot. Trước 17/11/2026 hạn mức chép vào learner_group.slot_limit lúc mở
 * nhóm (4 / 40) và mỗi tài khoản chỉ mở được một nhóm mỗi loại; nay hạn mức nằm trên TÀI KHOẢN, còn
 * nhóm chỉ khai sĩ số.
 *
 * Bảng phụ trách: SlotAddon (phần cộng thêm), Entitlement (kỳ gói), LearnerSlot (đã dùng).
 */
public interface SlotQuotaService {

    /** Slot mặc định của gói Parent (chốt Q2). */
    int PARENT_BASE_SLOTS = 4;
    /** Slot mặc định của gói Teacher (chốt Q3). */
    int TEACHER_BASE_SLOTS = 40;
    /** Trần ví phụ huynh, tính cả add-on (chốt Q2). */
    int PARENT_MAX_SLOTS = 10;
    /** Trần ví giáo viên mỗi kỳ, tính cả add-on (chốt Q3). */
    int TEACHER_MAX_SLOTS = 200;

    /** Ví của tài khoản theo loại nhóm: FAMILY → gói PARENT, CLASS → gói TEACHER. */
    SlotQuota quota(UUID accountId, LearningContext context);

    /** Ví của mọi loại gói tài khoản đang có — GET /api/me/quota. */
    List<SlotQuota> quotas(UUID accountId);
}
