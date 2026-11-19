package com.doan.game.DTO.response;

import com.doan.game.enums.AiProviderKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Một khoá AI — KHÔNG BAO GIỜ kèm khoá thật, chỉ masked "••••abcd". lastTestAt null = chưa thử. coolDownUntil khác null = đang
 * nghỉ vì hết lượt. enabled = false = admin đã dừng hoặc hệ thống tự tắt (lastError ghi lý do, mở đầu bằng AUTH / NO_CREDIT /
 * PRECONDITION). Đợt D: purpose, project, ownerNote, tierLabel, rpmLimit, rpdLimit.
 */
public record AiKeyResponse(UUID id, AiProviderKind provider, String alias, String masked, boolean enabled, Integer priority,
                            Instant lastTestAt, boolean lastTestOk, String lastError, Instant coolDownUntil,
                            String createdByName, Instant createdAt, String purpose, String project, String ownerNote,
                            String tierLabel, Integer rpmLimit, Integer rpdLimit) {
}
