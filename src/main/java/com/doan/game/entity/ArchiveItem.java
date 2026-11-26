package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một đối tượng (ảnh tải lên, tệp giọng đã sinh, bản phát hành) đang trong vòng đời lưu trữ. Không
 * có dòng = đối tượng bình thường. COUNTING: mồ côi, đếm tới dueAt (Giữ lại thì đếm lại);
 * ARCHIVING: máy lưu trữ đã nhận việc; ARCHIVED: tệp không còn trên máy chủ (bản phát hành vẫn nằm
 * trong DB, chỉ thôi tính là chỗ dùng tệp); RESTORING: đã yêu cầu khôi phục. BE chỉ quyết định và
 * ghi trạng thái, không xoá tệp. Ràng buộc: UNIQUE(kind, itemRef): một dòng cho mỗi đối tượng; job
 * và request chạy song song không tạo dòng trùng
 */
@Entity
@Table(name = "archive_item", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"kind", "item_ref"})
})
@Getter
@Setter
@NoArgsConstructor
public class ArchiveItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private ArchiveKind kind;

    @Column(name = "item_ref", length = 128)
    private String itemRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private ArchiveState state;

    @Column(name = "countdown_from")
    private Instant countdownFrom;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "kept_by_id", nullable = true)
    private Account keptBy;

    @Column(name = "kept_at")
    private Instant keptAt;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "restore_requested_by_id", nullable = true)
    private Account restoreRequestedBy;

    @Column(name = "restore_requested_at")
    private Instant restoreRequestedAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "last_error", length = 255)
    private String lastError;
}
