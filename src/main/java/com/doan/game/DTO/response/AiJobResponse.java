package com.doan.game.DTO.response;

import com.doan.game.entity.AiJob;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Một việc AI trên trang admin "Nhật ký việc AI" (Hưng 19/11): trạng thái sống, không bao giờ trả gói input (chỉ hash +
 * cỡ byte) và không trả output. keyAlias / currentKeyAlias là tên gợi nhớ, không phải khoá.
 */
public record AiJobResponse(UUID id, String kind, String status, Integer tier, String provider, String keyAlias,
                            String currentKeyAlias, Integer attempts, String lastErrorCode, String lastErrorText,
                            String inputHash, Integer inputBytes, Instant createdAt, Instant startedAt,
                            Instant nextAttemptAt, Instant lockedAt, Instant doneAt, String lane, UUID batchId) {

    public static AiJobResponse of(AiJob j) {
        return new AiJobResponse(j.getId(), j.getKind(), j.getStatus(), j.getTier(), j.getProvider(), j.getKeyAlias(),
                j.getCurrentKeyAlias(), j.getAttempts(), j.getLastErrorCode(), j.getLastErrorText(), j.getInputHash(),
                j.getInput() == null ? null : j.getInput().getBytes(StandardCharsets.UTF_8).length,
                j.getCreatedAt(), j.getStartedAt(), j.getNextAttemptAt(), j.getLockedAt(), j.getDoneAt(), j.getLane(), j.getBatchId());
    }
}
