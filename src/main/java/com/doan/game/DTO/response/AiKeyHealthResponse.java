package com.doan.game.DTO.response;

import com.doan.game.enums.AiProviderKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sức khoẻ khoá AI (đợt D, khung báo cáo AI 13.4). "Hôm nay" = ngày quota của Gemini (từ nửa đêm giờ Thái Bình Dương,
 * dayStartedAt). healthy = đang bật và không nghỉ. warning khác null khi còn dưới 2 khoá khoẻ. needsHuman = cần người làm
 * (khoá bị tự tắt vì AUTH / NO_CREDIT / PRECONDITION, hoặc không còn khoá khoẻ) — cùng điều kiện với log "AI-ALERT".
 */
public record AiKeyHealthResponse(Instant dayStartedAt, int healthyKeys, String warning, boolean needsHuman,
                                  List<KeyHealth> keys) {

    public record KeyHealth(UUID id, String alias, AiProviderKind provider, String purpose, String project,
                            String tierLabel, boolean enabled, Instant coolDownUntil, boolean healthy, long callsToday,
                            long okToday, Map<String, Long> outcomesToday, long tokensInToday, long tokensOutToday,
                            Instant lastOkAt, String lastError, Integer rpmLimit, Integer rpdLimit,
                            long batchesToday, long itemsToday) {
    }
}
