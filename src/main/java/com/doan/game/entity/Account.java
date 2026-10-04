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

    @Column(name = "must_change_password")
    private boolean mustChangePassword;

    @Column(name = "failed_attempts")
    private Integer failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * Phiên bản token, tăng thêm 1 mỗi lần MẬT KHẨU đổi (đặt lại qua mail hoặc đổi khi đang
     * đăng nhập). Token cũ mang phiên bản cũ nên bị RevocationAwareJwtDecoder từ chối.
     *
     * Cần cột này vì access token sống tới 168 giờ: bị lộ mật khẩu mà chỉ đổi mật khẩu thì kẻ
     * đang cầm token vẫn đi lại được cả tuần. Không đặt cờ trên token vì token là của kẻ xấu.
     *
     * Khi nào thu vai ADMIN hoặc thu gói cũng phải tăng số này — không thì vai/trong token cũ
     * vẫn còn tới khi token hết hạn.
     */
    /**
     * nullable=false + default 0 là BẮT BUỘC với cột mới thêm trên DB đã có dữ liệu:
     * ddl-auto=update tạo cột cho phép NULL, nạp tài khoản cũ là Hibernate ném
     * JpaSystemException "Null value was assigned to a property of primitive type" và
     * đăng nhập ra 500. Câu default 0 also điền sẵn 0 cho các dòng cũ khi ALTER chạy.
     */
    @Column(name = "token_version", nullable = false, columnDefinition = "integer default 0 not null")
    private int tokenVersion;
}
