package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một việc trong hàng chờ AI nhiều tầng (REPORT_SUMMARY nay, SPOKEN_TEXT sau). input = gói ẩn
 * danh, không có định danh của trẻ; inputHash = sha-256 của input, gói trùng thì dùng chung việc
 * và kết quả. status (chuỗi, Hưng 19/11): QUEUED → RUNNING → DONE | WAITING_RETRY (hẹn lại tới
 * nextAttemptAt) | WAITING_AGENT (tầng 3–4, phiếu Discord) | FAILED. Job lấy việc QUEUED /
 * WAITING_RETRY đã tới hạn (FOR UPDATE SKIP LOCKED); lockedAt để trả việc kẹt về hàng chờ; hết
 * lượt (429) thì hẹn lại, không tính attempts. keyAlias = khoá đã viết ra kết quả, currentKeyAlias
 * = khoá đang dùng (chỉ tên gợi nhớ, không bao giờ là khoá); lastErrorCode / lastErrorText = lỗi
 * gần nhất. Ràng buộc: Không cột nào trỏ về slot / nhóm / tài khoản: input là gói ẩn danh
 * (whitelist trường) · INDEX(status, lane, nextAttemptAt): hàng chờ lấy việc theo ngăn
 */
@Entity
@Table(name = "ai_job", indexes = {
    @Index(columnList = "status, lane, next_attempt_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AiJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "kind", length = 24)
    private String kind;

    @Column(name = "input", columnDefinition = "text")
    private String input;

    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "tier")
    private Integer tier;

    @Column(name = "provider", length = 24)
    private String provider;

    @Column(name = "key_alias", length = 40)
    private String keyAlias;

    @Column(name = "current_key_alias", length = 40)
    private String currentKeyAlias;

    @Column(name = "attempts")
    private Integer attempts;

    @Column(name = "output", columnDefinition = "text")
    private String output;

    @Column(name = "last_error_code", length = 40)
    private String lastErrorCode;

    @Column(name = "last_error_text", length = 500)
    private String lastErrorText;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "lane", length = 32)
    private String lane;

    @Column(name = "batch_id")
    private UUID batchId;
}
