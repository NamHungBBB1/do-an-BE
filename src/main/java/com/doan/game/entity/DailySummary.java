package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bài viết cho người lớn về một học sinh (19/11): kind CHAPTER (xong một chương, đã nộp hậu trắc),
 * FINAL (tổng kết 8 chương, chapter = 8) hoặc CLASS (bản lớp, groupId). summary = bài đang hiện:
 * bản theo luật (source RULE, tier 0) có ngay, tầng AI viết xong thì thay (source LLM).
 * packageHash = sha-256 gói số liệu ẩn danh (gói nằm ở AiJob.input); cùng hash thì dùng lại bài
 * cũ. Chơi lại chương = dòng mới theo attemptIndex. Ràng buộc: CHECK: đúng một trong slotId /
 * groupId khác NULL · UNIQUE(slotId, kind, chapter, attemptIndex) · UNIQUE(groupId, summaryDate)
 */
@Entity
@Table(name = "daily_summary", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"slot_id", "kind", "chapter", "attempt_index"}),
    @UniqueConstraint(columnNames = {"group_id", "summary_date"})
})
@Getter
@Setter
@NoArgsConstructor
public class DailySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** LearnerSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "slot_id", nullable = true)
    private LearnerSlot slot;

    /** LearnerGroup. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "group_id", nullable = true)
    private LearnerGroup group;

    @Column(name = "summary_date")
    private LocalDate summaryDate;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "source")
    private SummarySource source;

    @Column(name = "model", length = 80)
    private String model;

    @Column(name = "prompt_version", length = 32)
    private String promptVersion;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "kind", length = 16)
    private String kind;

    @Column(name = "chapter")
    private Integer chapter;

    @Column(name = "attempt_index")
    private Integer attemptIndex;

    @Column(name = "build_version", length = 16)
    private String buildVersion;

    @Column(name = "package_hash", length = 64)
    private String packageHash;

    @Column(name = "tier")
    private Integer tier;

    @Column(name = "provider", length = 24)
    private String provider;

    /** AiJob. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "ai_job_id", nullable = true)
    private AiJob aiJob;
}
