package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mỗi lần hộp thẻ thông tin của một cảnh (scene.inspect) được mở, kể cả mở lại, theo thứ tự seq
 * (play-v2 mục 4). Ràng buộc: UNIQUE(slotId, chapter, attemptIndex, seq): gửi lại lô không tạo
 * dòng trùng
 */
@Entity
@Table(name = "inspect_event", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"slot_id", "chapter", "attempt_index", "seq"})
})
@Getter
@Setter
@NoArgsConstructor
public class InspectEvent {

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

    @Column(name = "seq")
    private Integer seq;

    @Column(name = "scene_id", length = 64)
    private String sceneId;

    @Column(name = "inspect_id", length = 64)
    private String inspectId;

    @Column(name = "client_at")
    private Instant clientAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "build_version", length = 32)
    private String buildVersion;
}
