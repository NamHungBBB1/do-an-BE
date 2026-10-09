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
 * Tổng kết hằng ngày. Parent: một bản mỗi slot; Teacher: một bản mỗi slot + một bản cho cả lớp
 * (groupId). source = RULE khi AI lỗi và dùng bản theo luật. UNIQUE theo ngày để job chạy lại
 * không sinh bản thứ hai. Ràng buộc: CHECK: đúng một trong slotId / groupId khác NULL ·
 * UNIQUE(slotId, summaryDate) · UNIQUE(groupId, summaryDate)
 */
@Entity
@Table(name = "daily_summary", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"slot_id", "summary_date"}),
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
}
