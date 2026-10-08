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
        return new LearnerGroupResponse(
                source.getId(),
                source.getName(),
                source.getContext() == null ? null : source.getContext().name(),
                source.getSlotLimit() == null ? 0 : source.getSlotLimit(),
                slotUsed,
                source.getConsentConfirmedAt() != null,
                source.getClosedAt());
    }
}
