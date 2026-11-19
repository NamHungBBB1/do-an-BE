package com.doan.game.DTO.response;

import com.doan.game.entity.AiCallLog;

import java.time.Instant;
import java.util.UUID;

/**
 * Một lần gọi HTTP tới nhà cung cấp AI (trang admin "Nhật ký gọi AI", đợt D). Không có khoá, gói hay prompt; providerMessage
 * đã lọc khoá và che số project. humanText = chuyện gì xảy ra / hệ thống đã làm gì / người phải làm gì.
 */
public record AiCallLogResponse(UUID id, Instant createdAt, UUID aiJobId, Integer attempt, String kind, String keyAlias,
                                String provider, String model, Integer httpStatus, String providerStatus,
                                String providerMessage, String quotaMetric, Integer retryDelaySeconds, Integer latencyMs,
                                Integer tokensIn, Integer tokensOut, String outcome, String humanText,
                                String rejectedRule) {

    public static AiCallLogResponse of(AiCallLog l) {
        return new AiCallLogResponse(l.getId(), l.getCreatedAt(), l.getAiJobId(), l.getAttempt(), l.getKind(),
                l.getKeyAlias(), l.getProvider(), l.getModel(), l.getHttpStatus(), l.getProviderStatus(),
                l.getProviderMessage(), l.getQuotaMetric(), l.getRetryDelaySeconds(), l.getLatencyMs(), l.getTokensIn(),
                l.getTokensOut(), l.getOutcome(), l.getHumanText(), l.getRejectedRule());
    }
}
