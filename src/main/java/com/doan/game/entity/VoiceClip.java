package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một câu giọng đọc đã / đang sinh, khoá là hash(engine | model | giọng | chữ đọc). Câu có khoá đã
 * DONE thì dùng lại tệp cũ; chỉ câu đổi chữ / đổi giọng mới phải sinh. Ràng buộc: clipKey là hash
 * nội dung: cùng giọng + cùng chữ đọc thì dùng lại tệp cũ, không sinh lại
 */
@Entity
@Table(name = "voice_clip")
@Getter
@Setter
@NoArgsConstructor
public class VoiceClip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clip_key", length = 20, unique = true)
    private String clipKey;

    @Column(name = "profile", length = 40)
    private String profile;

    @Column(name = "model_version", length = 40)
    private String modelVersion;

    @Column(name = "read_text", columnDefinition = "text")
    private String readText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private VoiceClipStatus status;

    @Column(name = "path", length = 255)
    private String path;

    @Column(name = "seconds", precision = 7, scale = 2)
    private BigDecimal seconds;

    @Column(name = "error", length = 255)
    private String error;

    @Column(name = "chapter_code", length = 8)
    private String chapterCode;

    @Column(name = "scene_id", length = 80)
    private String sceneId;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "requested_by_id", nullable = true)
    private Account requestedBy;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "done_at")
    private Instant doneAt;
}
