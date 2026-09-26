package com.doan.game.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ước lượng knowledge tracing cho một người học và một khái niệm: xác suất đã nắm, và dựa trên bao
 * nhiêu câu trả lời. Tính lại được từ MiniGameResult bất cứ lúc nào.
 */
@Entity
@Table(name = "concept_mastery")
@Getter
@Setter
@NoArgsConstructor
public class ConceptMastery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ChildSlot slot;

    @Column(name = "concept", length = 40)
    private String concept;

    @Column(name = "p_mastery", precision = 5, scale = 4)
    private BigDecimal pMastery;

    @Column(name = "answers")
    private Integer answers;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
