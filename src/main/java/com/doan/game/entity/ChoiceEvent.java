package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lựa chọn người học đưa ra trong đoạn thoại. KHÔNG có đáp án đúng — cột effects ghi lựa chọn
 * đó làm năm chỉ số dịch chuyển ra sao. Cố ý tách khỏi MiniGameResult: nhét sở thích chung bảng
 * với đáp án đúng–sai thì hoặc để một cột rỗng vĩnh viễn, hoặc mời người ta đi chấm điểm một sở
 * thích.
 */
@Entity
@Table(name = "choice_event")
@Getter
@Setter
@NoArgsConstructor
public class ChoiceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ChildSlot slot;

    @Column(name = "chapter")
    private Integer chapter;

    @Column(name = "attempt_index")
    private Integer attemptIndex;

    @Column(name = "scene_id", length = 64)
    private String sceneId;

    @Column(name = "choice_id", length = 64)
    private String choiceId;

    @Column(name = "effects")
    private String effects;

    @Column(name = "build_version", length = 32)
    private String buildVersion;

    @Column(name = "chosen_at")
    private Instant chosenAt;

    @Column(name = "received_at")
    private Instant receivedAt;
}
