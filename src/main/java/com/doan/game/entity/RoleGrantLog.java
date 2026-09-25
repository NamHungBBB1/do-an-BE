package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Nhật ký cho mọi lần đổi vai bằng tay. Trả lời đúng câu người rà soát sẽ hỏi: vì sao tài khoản
 * này giữ vai giáo viên mà không có khoản thanh toán nào?
 */
@Entity
@Table(name = "role_grant_log")
@Getter
@Setter
@NoArgsConstructor
public class RoleGrantLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private Account actor;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    private Account target;

    @Column(name = "role", length = 16)
    private String role;

    @Column(name = "granted")
    private boolean granted;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at")
    private Instant createdAt;
}
