package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Báo cáo tổng kết lớp, đông cứng đúng lúc nhóm đóng và không bao giờ tính lại. Công thức chấm
 * điểm chưa chốt và sẽ còn đổi; tính lại là âm thầm làm đổi một báo cáo cô giáo đã phát ra rồi.
 */
@Entity
@Table(name = "group_report")
@Getter
@Setter
@NoArgsConstructor
public class GroupReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** LearnerGroup. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private LearnerGroup group;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "formula_version", length = 32)
    private String formulaVersion;

    @Column(name = "summary")
    private String summary;
}
