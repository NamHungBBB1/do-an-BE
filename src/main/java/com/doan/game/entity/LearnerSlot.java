package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Danh tính của một trẻ. Mở slot (tên + PIN) mới tạo dòng và sinh mã chữ; slot đã tạo tính vào ví
 * mãi mãi (18/11, slot vĩnh viễn). Sai PIN 5 lần khoá 15 phút (tính từ lockedUntil). Xoá = WIPED:
 * xoá tên, PIN, cá nhân hoá, badge (Luật BVDLCN 2025), GIỮ mã để không cấp lại, giữ kết quả học ẩn
 * danh. tokenVersion: một phiên mỗi slot.
 */
@Entity
@Table(name = "learner_slot")
@Getter
@Setter
@NoArgsConstructor
public class LearnerSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** LearnerGroup. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private LearnerGroup group;

    @Column(name = "code", length = 16, unique = true)
    private String code;

    @Column(name = "pin_hash", length = 72)
    private String pinHash;

    @Column(name = "display_name", length = 40)
    private String displayName;

    @Column(name = "badge", length = 40)
    private String badge;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SlotStatus status;

    @Column(name = "failed_attempts")
    private Integer failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "wiped_at")
    private Instant wipedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "personalization", columnDefinition = "text")
    private String personalization;

    @Column(name = "token_version")
    private Integer tokenVersion;
}
