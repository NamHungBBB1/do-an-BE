package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bản tổng kết mỗi đêm do mô hình ngôn ngữ bên thứ ba viết, cho một slot hoặc cho cả lớp. Giữ
 * model và promptVersion vì đổi mô hình là đổi giọng văn và kết luận. Mỗi ngày một dòng nên chạy
 * lại job chỉ ghi đè.
 */
@Entity
@Table(name = "daily_summary")
@Getter
@Setter
@NoArgsConstructor
public class DailySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "slot_id", nullable = true)
    private ChildSlot slot;

    /** LearnerGroup. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "group_id", nullable = true)
    private LearnerGroup group;

    @Column(name = "summary_date")
    private LocalDate summaryDate;

    @Column(name = "summary", length = 8000)
    private String summary;

    @Column(name = "model", length = 80)
    private String model;

    @Column(name = "prompt_version", length = 32)
    private String promptVersion;

    @Column(name = "created_at")
    private Instant createdAt;
}
