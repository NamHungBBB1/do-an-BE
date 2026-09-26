package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một người lớn, hoặc chính admin. Vai phụ huynh và giáo viên SUY RA từ gói đang giữ; vai admin là
 * cờ admin (thêm 26/09), chỉ bật lúc gieo dữ liệu khi khởi động, vì admin không mua gói. Trẻ không
 * bao giờ có một dòng ở đây.
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

    @Column(name = "parent_plan")
    private boolean parentPlan;

    @Column(name = "teacher_plan")
    private boolean teacherPlan;

    @Column(name = "admin")
    private boolean admin;

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
}
