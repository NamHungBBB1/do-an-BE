package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Đề giáo viên tự chọn câu từ kho chung, phát cho một nhóm CLASS, có hạn nộp. Child không bao giờ
 * có quiz. 16/10: kind = CLASS_TEST (giáo viên ra đề) hoặc POST_TEST (hậu trắc, BE tự tạo cho lớp
 * bật cờ khi em xong chương, không hạn nộp); chapter = chương của đề hậu trắc. NULL = đề giáo viên
 * (dòng cũ). Ràng buộc: UNIQUE(groupId, kind, chapter): mỗi lớp mỗi chương đúng một đề hậu trắc
 * (đề giáo viên có chapter NULL nên không vướng)
 */
@Entity
@Table(name = "quiz", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"group_id", "kind", "chapter"})
})
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

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private QuizKind kind;

    @Column(name = "chapter")
    private Integer chapter;
}
