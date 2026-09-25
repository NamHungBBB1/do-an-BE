package com.doan.game.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Một chỗ ngồi cho trẻ: mã QR + PIN do người lớn tạo và quản lý.
 *
 * Chốt: trẻ KHÔNG tự đăng ký. Phụ huynh tạo tối đa 4, giáo viên có 40 slot.
 * Ở lớp trẻ tự gõ tên mình — cô giáo chịu trách nhiệm bắt gõ đúng; phần mềm không ép được.
 *
 * Chốt: slot là GIẤY THUÊ CÓ THỜI HẠN, không phải danh tính vĩnh viễn. Bấm "kết thúc lớp học"
 * là lưu lại data rồi TRẢ SLOT về để tái dùng. Trẻ đã liên kết tài khoản thì vẫn vào được
 * sau đó; trẻ chưa liên kết thì mất đường vào — đó là lý do mạnh nhất của nút liên kết.
 */
@Entity
@Table(name = "child_slot", indexes = {
        @Index(name = "idx_slot_code", columnList = "code", unique = true),
        @Index(name = "idx_slot_owner", columnList = "owner_id"),
        @Index(name = "idx_slot_linked", columnList = "linked_account_id")
})
public class ChildSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nội dung mã QR. Chữ hoa, bỏ ký tự dễ nhìn nhầm: 0/O và 1/I/L. */
    @Column(nullable = false, length = 16, unique = true)
    private String code;

    /** BCrypt của PIN. PIN gốc chỉ hiện ĐÚNG MỘT LẦN lúc tạo, không có đường đọc lại. */
    @Column(name = "pin_hash", nullable = false, length = 72)
    private String pinHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    /** Người lớn sở hữu slot. Một người vừa là phụ huynh vừa là giáo viên thì cùng id này. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** FAMILY hay CLASS — lưu HẲN, không suy từ vai của chủ, vì chủ có thể giữ cả hai vai. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private LearningContext context;

    /** Tài khoản trẻ đã liên kết, nếu có. Null = chỉ vào được bằng QR + PIN. */
    @Column(name = "linked_account_id")
    private UUID linkedAccountId;

    /**
     * LEVEL — cùng với danh hiệu là thứ DUY NHẤT đi qua ranh giới nhà/lớp.
     * Cô giáo phải biết bé đã từng chơi trước đó, không thì so sánh vô nghĩa
     * (bé chơi 50 giờ ở nhà so với bé chưa chơi bao giờ). Nhưng LỊCH SỬ thì không đi qua.
     */
    @Column(nullable = false)
    private int level = 1;

    @Column(length = 40)
    private String badge;

    /** Lớp đã kết thúc: data giữ lại, slot không tính vào hạn mức nữa, PIN hết vào được. */
    @Column(nullable = false)
    private boolean archived;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Chống dò PIN. 6 chữ số là 1 triệu tổ hợp — không khoá thì dò xong trong một buổi. */
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getPinHash() { return pinHash; }
    public void setPinHash(String v) { this.pinHash = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID v) { this.ownerId = v; }
    public LearningContext getContext() { return context; }
    public void setContext(LearningContext v) { this.context = v; }
    public UUID getLinkedAccountId() { return linkedAccountId; }
    public void setLinkedAccountId(UUID v) { this.linkedAccountId = v; }
    public int getLevel() { return level; }
    public void setLevel(int v) { this.level = v; }
    public String getBadge() { return badge; }
    public void setBadge(String v) { this.badge = v; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean v) { this.archived = v; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant v) { this.archivedAt = v; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int v) { this.failedAttempts = v; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant v) { this.lockedUntil = v; }
    public Instant getCreatedAt() { return createdAt; }
}
