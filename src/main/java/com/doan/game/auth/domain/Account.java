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

    /**
     * Null = chưa bấm link trong mail. Đây là CÁNH CỬA CHẶN SPAM thật sự:
     * đăng ký thì ai cũng đăng ký được, nhưng chưa xác thực thì không mua gói,
     * không tạo được slot nào — tức không tiêu tài nguyên gì của hệ thống.
     */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /**
     * SHA-256 của token trong link, KHÔNG lưu token gốc: nó là một thứ bearer nằm
     * trên URL, ai đọc được DB mà có token gốc là tự xác thực hộ người khác được.
     */
    @Column(name = "verify_token_hash", length = 64)
    private String verifyTokenHash;

    @Column(name = "verify_token_expires_at")
    private Instant verifyTokenExpiresAt;

    /** Để chặn bấm "gửi lại" liên tục — mỗi lần gửi lại là một mail thật đi ra. */
    @Column(name = "verify_token_sent_at")
    private Instant verifyTokenSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public boolean isEmailVerified() { return emailVerifiedAt != null; }

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
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(Instant v) { this.emailVerifiedAt = v; }
    public String getVerifyTokenHash() { return verifyTokenHash; }
    public void setVerifyTokenHash(String v) { this.verifyTokenHash = v; }
    public Instant getVerifyTokenExpiresAt() { return verifyTokenExpiresAt; }
    public void setVerifyTokenExpiresAt(Instant v) { this.verifyTokenExpiresAt = v; }
    public Instant getVerifyTokenSentAt() { return verifyTokenSentAt; }
    public void setVerifyTokenSentAt(Instant v) { this.verifyTokenSentAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
