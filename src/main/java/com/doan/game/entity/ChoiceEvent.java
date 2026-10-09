package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lựa chọn người học đưa ra trong đoạn thoại: cảnh nào, nút nào, bản phát hành nào. KHÔNG có
 * đáp án đúng. effects là hiệu ứng BE tra từ bản phát hành rồi ghi lại (08/10), không phải số
 * client gửi. Ràng buộc: UNIQUE(slotId, chapter, attemptIndex, sceneId): chống ghi trùng khi gửi
 * lại lô
 */
@Entity
@Table(name = "choice_event", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"slot_id", "chapter", "attempt_index", "scene_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class ChoiceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** LearnerSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private LearnerSlot slot;

    @Column(name = "chapter")
    private Integer chapter;

    @Column(name = "attempt_index")
    private Integer attemptIndex;

    @Column(name = "scene_id", length = 64)
    private String sceneId;

    @Column(name = "choice_id", length = 64)
    private String choiceId;

    @Column(name = "effects", columnDefinition = "text")
    private String effects;

    @Column(name = "build_version", length = 32)
    private String buildVersion;

    @Column(name = "chosen_at")
    private Instant chosenAt;

    @Column(name = "received_at")
    private Instant receivedAt;
}
