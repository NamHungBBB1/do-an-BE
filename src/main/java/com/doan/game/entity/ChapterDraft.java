package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bản nháp của một chương (JSON cùng hình dạng game đang chạy). Nháp → Gửi duyệt → Đạt / Không
 * đạt; không ai duyệt bản mình sửa; khoá mềm 30 phút khi đang sửa (lockedBy, lockedUntil);
 * changeNote là "lý do sửa" (giữ phần hay của ticket, 09/10); reviewNote là nhận xét khi Không
 * đạt; revision tăng mỗi lần lưu để chặn lưu chồng. Ràng buộc: Mỗi chương tối đa một bản nháp chưa
 * phát hành (service kiểm)
 */
@Entity
@Table(name = "chapter_draft")
@Getter
@Setter
@NoArgsConstructor
public class ChapterDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Chapter. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private DraftStatus status;

    @Column(name = "change_note", length = 255)
    private String changeNote;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "editor_id", nullable = true)
    private Account editor;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "locked_by_id", nullable = true)
    private Account lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "reviewer_id", nullable = true)
    private Account reviewer;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "revision", nullable = false, columnDefinition = "integer default 0 not null")
    private int revision;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
