package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lần gọi HTTP tới nhà cung cấp AI (đợt D, khung báo cáo AI 13.4): alias khoá, nhà cung cấp,
 * model, mã HTTP, lỗi nguyên văn đã lọc khoá và che số project, chỉ số quota bị chạm, retryDelay,
 * thời gian chạy, token vào / ra, kết quả (OK | QUOTA_MINUTE | QUOTA_DAY | AUTH | BAD_REQUEST |
 * PRECONDITION | NOT_FOUND | SERVER | TIMEOUT | SAFETY | REJECTED_OUTPUT | NO_CREDIT) và câu diễn
 * giải tiếng Việt: chuyện gì xảy ra, hệ thống đã làm gì, người phải làm gì. aiJobId là id rời
 * (không khoá ngoại). Ràng buộc: Không lưu khoá, gói số liệu hay prompt; giữ 30 ngày
 */
@Entity
@Table(name = "ai_call_log")
@Getter
@Setter
@NoArgsConstructor
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "ai_job_id")
    private UUID aiJobId;

    @Column(name = "attempt")
    private Integer attempt;

    @Column(name = "kind", length = 24)
    private String kind;

    @Column(name = "key_alias", length = 40)
    private String keyAlias;

    @Column(name = "provider", length = 24)
    private String provider;

    @Column(name = "model", length = 80)
    private String model;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "provider_status", length = 40)
    private String providerStatus;

    @Column(name = "provider_message", length = 500)
    private String providerMessage;

    @Column(name = "quota_metric", length = 160)
    private String quotaMetric;

    @Column(name = "retry_delay_seconds")
    private Integer retryDelaySeconds;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "outcome", length = 24)
    private String outcome;

    @Column(name = "human_text", length = 500)
    private String humanText;

    @Column(name = "rejected_rule", length = 160)
    private String rejectedRule;
}
