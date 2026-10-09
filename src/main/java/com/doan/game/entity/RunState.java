package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Trạng thái bền của một người học: đã xong chương nào và năm chỉ số ẩn cộng dồn suốt cả lượt.
 * 08/10 (Hưng chốt): BE TỰ TÍNH từ ChoiceEvent theo bản phát hành đã đóng băng (ReleaseChapter),
 * không tin số tổng do máy khách gửi lên. Ràng buộc: CHECK: 5 chỉ số trong 0..100
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

    /** LearnerSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false, unique = true)
    private LearnerSlot slot;

    @Column(name = "chapters_done", columnDefinition = "text")
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
