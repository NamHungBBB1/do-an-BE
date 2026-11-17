package com.doan.game.mapper;

import com.doan.game.DTO.response.SlotResponse;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.SlotStatus;

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
        // WIPED: mã vẫn nằm trong DB (không cấp lại cho bé khác) nhưng KHÔNG trả ra API (18/11/2026).
        boolean wiped = source.getStatus() == SlotStatus.WIPED;
        return new SlotResponse(
                source.getId(),
                wiped ? null : source.getCode(),
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
