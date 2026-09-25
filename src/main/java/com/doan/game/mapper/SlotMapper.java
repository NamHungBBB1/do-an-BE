package com.doan.game.mapper;

import com.doan.game.DTO.response.SlotResponse;
import com.doan.game.entity.ChildSlot;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: lộ một cột nội bộ
 * ra ngoài thì về sau không rút lại được nữa.
 */
public final class SlotMapper {

    private SlotMapper() {
    }

    public static SlotResponse sang(ChildSlot nguon) {
        throw new UnsupportedOperationException("chua cai dat");
    }
}
