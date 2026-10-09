package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một chương của game (CH01…CH08): chỉ mã, số thứ tự, tên. Nội dung nằm ở bản nháp (ChapterDraft)
 * và bản đóng băng theo phiên bản (ReleaseChapter), không ở đây.
 */
@Entity
@Table(name = "chapter")
@Getter
@Setter
@NoArgsConstructor
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", length = 16, unique = true)
    private String code;

    @Column(name = "chapter_number")
    private Integer chapterNumber;

    @Column(name = "title", length = 120)
    private String title;

    @Column(name = "created_at")
    private Instant createdAt;
}
