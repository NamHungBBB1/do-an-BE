package com.doan.game.mapper;

import com.doan.game.DTO.response.EntitlementResponse;
import com.doan.game.entity.Entitlement;

/**
 * Mapper viết tay, phương thức tĩnh — đúng khuôn SWP, không dùng MapStruct.
 *
 * Tầng này tồn tại để entity KHÔNG bao giờ lọt thẳng ra API: lộ một cột nội bộ
 * ra ngoài thì về sau không rút lại được nữa.
 */
public final class EntitlementMapper {

    private EntitlementMapper() {
    }

    public static EntitlementResponse toResponse(Entitlement source) {
        return new EntitlementResponse(
                source.getId(),
                source.getKind() == null ? null : source.getKind().name(),
                source.getStartsOn(),
                source.getExpiresOn(),
                source.getSource() == null ? null : source.getSource().name());
    }
}
