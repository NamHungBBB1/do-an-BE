package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một người lớn. Không còn cột gói hay cờ admin: Parent / Teacher suy ra từ Entitlement còn hạn;
 * vai nội bộ (ADMIN, sau này EDITOR / REVIEWER / MANAGER) nằm ở AccountRole.
 */
@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "email", length = 160, unique = true)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "must_change_password", nullable = false, columnDefinition = "boolean default false not null")
    private boolean mustChangePassword;

    @Column(name = "failed_attempts")
    private Integer failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "token_version", nullable = false, columnDefinition = "integer default 0 not null")
    private int tokenVersion;
}
