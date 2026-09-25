package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bài làm của một học sinh cho một đề. Gắn vào slot, nên không bao giờ lọt sang phạm vi gia đình.
 */
@Entity
@Table(name = "quiz_result")
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

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ChildSlot slot;

    @Column(name = "score")
    private Integer score;

    @Column(name = "submitted_at")
    private Instant submittedAt;
}
