package com.doan.game.mapper;

import com.doan.game.DTO.response.LearnerGroupResponse;
import com.doan.game.entity.LearnerGroup;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: lộ một cột nội bộ
 * ra ngoài thì về sau không rút lại được nữa.
 */
public final class LearnerGroupMapper {

    private LearnerGroupMapper() {
    }

    /** Vừa mở nhóm thì chưa có slot nào. */
    public static LearnerGroupResponse toResponse(LearnerGroup source) {
        return toResponse(source, 0);
    }

    /** slotUsed phải do service đếm (slot ACTIVE) — mapper không tự tra repo. */
    public static LearnerGroupResponse toResponse(LearnerGroup source, int slotUsed) {
        return toResponse(source, slotUsed, source.getSlotLimit() == null ? 0 : source.getSlotLimit());
    }

    /**
     * slotLimit hiệu lực do service tính (17/11/2026): FAMILY = ví của tài khoản (base + add-on) nên
     * mua thêm slot là con số này tự lớn; CLASS = sĩ số giáo viên đặt, bỏ trống thì lấy ví còn lại.
     * Không đọc thẳng learner_group.slot_limit vì cột đó chỉ là sĩ số khai báo của lớp.
     */
    public static LearnerGroupResponse toResponse(LearnerGroup source, int slotUsed, int slotLimit) {
        return new LearnerGroupResponse(
                source.getId(),
                source.getName(),
                source.getContext() == null ? null : source.getContext().name(),
                slotLimit,
                slotUsed,
                source.getConsentConfirmedAt() != null,
                source.getClosedAt(),
                source.getPostTestEnabledAt() != null);   // 16/10 quiz v2: cờ hậu trắc của lớp
    }
}
