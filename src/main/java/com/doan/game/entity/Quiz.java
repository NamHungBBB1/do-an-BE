package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Đề giáo viên tự chọn câu từ kho chung, phát cho một nhóm CLASS, có hạn nộp. Child không bao giờ
 * có quiz.
 */
@Entity
@Table(name = "quiz")
@Getter
@Setter
@NoArgsConstructor
public class Quiz {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Account owner;

    /** LearnerGroup. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private LearnerGroup group;

    @Column(name = "title", length = 120)
    private String title;

    @Column(name = "question_ids", columnDefinition = "text")
    private String questionIds;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
