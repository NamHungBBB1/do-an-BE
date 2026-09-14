package com.doan.game.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tài khoản của NGƯỜI LỚN (phụ huynh / giáo viên), và của trẻ đã tự tạo tài khoản
 * để liên kết slot.
 *
 * Chốt: người lớn đăng nhập bằng Gmail + số điện thoại. Trẻ KHÔNG có hàng ở đây
 * trừ khi tự tạo tài khoản để liên kết — tính năng optional.
 *
 * Vì sao gói là hai cờ boolean chứ không phải một cột role: một người có thể vừa mua
 * gói phụ huynh cho con ở nhà, vừa mua gói giáo viên cho lớp ở trường. Một cột enum
 * không diễn tả được, và đổi sau là phải migrate.
 */
@Entity
@Table(name = "account", indexes = @Index(name = "idx_acc_email", columnList = "email", unique = true))
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 160, unique = true)
    private String email;

    /** Chốt: Gmail + SĐT. SĐT mới chỉ LƯU, chưa xác thực — xác thực cần SMS mà SMS tốn tiền. */
    @Column(nullable = false, length = 20)
    private String phone;

    /** BCrypt. Không bao giờ trả ra ngoài, kể cả trong log. */
    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    /** Đã mua gói phụ huynh chưa. Chốt: cả hai gói đều MẤT PHÍ — tiền là cánh cửa chặn spam. */
    @Column(name = "parent_plan", nullable = false)
    private boolean parentPlan;

    /** Đã mua gói giáo viên chưa. Chốt: TRƯỜNG mua cho giáo viên, không bắt cô bỏ tiền túi. */
    @Column(name = "teacher_plan", nullable = false)
    private boolean teacherPlan;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    /** Vai suy ra từ gói, không lưu. Chưa mua gì thì là khách. */
    public Set<Role> roles() {
        EnumSet<Role> r = EnumSet.noneOf(Role.class);
        if (parentPlan) r.add(Role.PARENT);
        if (teacherPlan) r.add(Role.TEACHER);
        if (r.isEmpty()) r.add(Role.GUEST);
        return r;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public boolean isParentPlan() { return parentPlan; }
    public void setParentPlan(boolean v) { this.parentPlan = v; }
    public boolean isTeacherPlan() { return teacherPlan; }
    public void setTeacherPlan(boolean v) { this.teacherPlan = v; }
    public Instant getCreatedAt() { return createdAt; }
}
