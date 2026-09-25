package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Trạng thái bền của một người học: đã xong chương nào và năm chỉ số — chúng cộng dồn suốt cả lượt
 * chứ không reset mỗi chương. Máy khách gửi số tổng nên lô gửi lại chỉ ghi đè vô hại.
 */
@Entity
@Table(name = "run_state")
@Getter
@Setter
@NoArgsConstructor
public class RunState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ChildSlot slot;

    @Column(name = "chapters_done")
    private String chaptersDone;

    @Column(name = "wealth")
    private Integer wealth;

    @Column(name = "saving")
    private Integer saving;

    @Column(name = "happiness")
    private Integer happiness;

    @Column(name = "risk")
    private Integer risk;

    @Column(name = "goal")
    private Integer goal;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
