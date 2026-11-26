package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mỗi lần trẻ bấm một phương án ở cảnh lựa chọn được trình chơi chấp nhận, theo thứ tự seq
 * (play-v2 mục 3). BE tự rút nhóm quyết định, dòng quyết định (phương án không có deferLedger),
 * lựa chọn có hiệu lực (dòng quyết định cuối của nhóm) và lần chọn đầu từ bản phát hành đã đóng
 * băng. clientAt là giờ máy trẻ (trường at của lô). Ràng buộc: UNIQUE(slotId, chapter,
 * attemptIndex, seq): gửi lại lô không tạo dòng trùng
 */
@Entity
@Table(name = "pick_event", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"slot_id", "chapter", "attempt_index", "seq"})
})
@Getter
@Setter
@NoArgsConstructor
public class PickEvent {

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

    @Column(name = "option_id", length = 64)
    private String optionId;

    @Column(name = "group_id", length = 64)
    private String groupId;

    @Column(name = "decision", nullable = false, columnDefinition = "boolean default false not null")
    private boolean decision;

    @Column(name = "effective", nullable = false, columnDefinition = "boolean default false not null")
    private boolean effective;

    @Column(name = "first_decision", nullable = false, columnDefinition = "boolean default false not null")
    private boolean firstDecision;

    @Column(name = "client_at")
    private Instant clientAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "build_version", length = 32)
    private String buildVersion;
}
