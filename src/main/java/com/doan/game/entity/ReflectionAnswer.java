package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Câu trả lời một hoạt động phản tư (mã ô → mã phương án), chỉ nhận cho cảnh phản tư có khai
 * inputs trong bản phát hành đã đóng băng (play-v2 mục 6). Chưa chấm: luật chấm chờ đợt nội dung.
 * Ràng buộc: UNIQUE(slotId, chapter, attemptIndex, sceneId): mỗi cảnh phản tư nộp một lần
 */
@Entity
@Table(name = "reflection_answer", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"slot_id", "chapter", "attempt_index", "scene_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class ReflectionAnswer {

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

    @Column(name = "answer", columnDefinition = "text")
    private String answer;

    @Column(name = "client_at")
    private Instant clientAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "build_version", length = 32)
    private String buildVersion;
}
