package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Nhật ký chỉ thêm của vòng đời lưu trữ: bắt đầu / bỏ đếm, giữ lại, nhận việc, đã lưu trữ, lỗi,
 * yêu cầu khôi phục, đã khôi phục, đối soát, lượt job bị phanh. actorId NULL = hệ thống hoặc máy
 * lưu trữ. Ràng buộc: Chỉ thêm, không sửa / xoá · INDEX(createdAt): nhật ký mới nhất trước
 */
@Entity
@Table(name = "archive_log", indexes = {
    @Index(columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class ArchiveLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private ArchiveKind kind;

    @Column(name = "item_ref", length = 128)
    private String itemRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private ArchiveAction action;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "actor_id", nullable = true)
    private Account actor;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "note", length = 255)
    private String note;
}
