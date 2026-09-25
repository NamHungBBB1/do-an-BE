package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một chỗ ngồi cho một đứa trẻ: bản thân nó CHÍNH LÀ thông tin đăng nhập. Là một suất thuê chứ
 * không phải danh tính vĩnh viễn — kết thúc lớp là lưu trữ nó lại và trả chỗ về cho lớp dùng tiếp.
 */
@Entity
@Table(name = "child_slot")
@Getter
@Setter
@NoArgsConstructor
public class ChildSlot {

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

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(name = "badge", length = 40)
    private String badge;

    @Column(name = "archived")
    private boolean archived;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "failed_attempts")
    private Integer failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at")
    private Instant createdAt;
}
