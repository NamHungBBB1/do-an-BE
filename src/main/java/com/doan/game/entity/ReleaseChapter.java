package com.doan.game.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Nội dung đóng băng của một chương trong một phiên bản: đúng cục JSON game tải (ảnh / giọng đã
 * ghép thành {{ASSET_BASE}}/a/<hash>, BE thay tiền tố lúc trả). Không bao giờ sửa sau khi phát
 * hành; contentHash để so hai bản. Ràng buộc: UNIQUE(releaseId, chapterId)
 */
@Entity
@Table(name = "release_chapter", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"release_id", "chapter_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class ReleaseChapter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** GameRelease. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id", nullable = false)
    private GameRelease release;

    /** Chapter. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id", nullable = false)
    private Chapter chapter;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "content_hash", length = 64)
    private String contentHash;
}
