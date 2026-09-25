package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cái hộp giữa người lớn và các chỗ ngồi: một gia đình hoặc một lớp học. Giữ hạn mức 4/40 và ngày
 * mở, ngày đóng. Tài khoản sống nhiều năm còn lớp chết mỗi kỳ, nên treo chỗ ngồi thẳng vào tài
 * khoản là trộn ba lứa học sinh với nhau vĩnh viễn.
 */
@Entity
@Table(name = "learner_group")
@Getter
@Setter
@NoArgsConstructor
public class LearnerGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Account owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "context")
    private LearningContext context;

    @Column(name = "name", length = 80)
    private String name;

    @Column(name = "slot_limit")
    private Integer slotLimit;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;
}
