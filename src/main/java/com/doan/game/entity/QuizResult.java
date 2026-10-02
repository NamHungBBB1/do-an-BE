package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bài làm của một học sinh. Mỗi em làm 1 lần; chưa mở quiz thì không có dòng. Ràng buộc:
 * UNIQUE(quizId, slotId): mỗi em làm 1 lần
 */
@Entity
@Table(name = "quiz_result", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"quiz_id", "slot_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class QuizResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Quiz. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    /** LearnerSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private LearnerSlot slot;

    @Column(name = "score")
    private Integer score;

    @Column(name = "submitted_at")
    private Instant submittedAt;
}
