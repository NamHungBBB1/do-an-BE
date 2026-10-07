package com.doan.game.mapper;

import com.doan.game.DTO.response.SlotResponse;
import com.doan.game.entity.LearnerSlot;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: lộ một cột nội bộ
 * ra ngoài thì về sau không rút lại được nữa.
 */
public final class SlotMapper {

    private SlotMapper() {
    }

    /** locked tính từ lockedUntil (đã do service so với now) — mapper không có đồng hồ. */
    public static SlotResponse toResponse(LearnerSlot source, boolean locked) {
        return new SlotResponse(
                source.getId(),
                source.getCode(),
                source.getDisplayName(),
                source.getBadge(),
                source.getStatus() == null ? null : source.getStatus().name(),
                locked);
    }

    /** Slot mới mở / chưa từng sai PIN thì không bị khoá. */
    public static SlotResponse toResponse(LearnerSlot source) {
        boolean locked = source.getLockedUntil() != null
                && source.getLockedUntil().isAfter(java.time.Instant.now());
        return toResponse(source, locked);
    }
}
