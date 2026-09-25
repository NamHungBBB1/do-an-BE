package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một đề trắc nghiệm giáo viên ghép từ kho câu hỏi dùng chung.
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

    @Column(name = "title", length = 120)
    private String title;

    @Column(name = "question_ids")
    private String questionIds;

    @Column(name = "published_at")
    private Instant publishedAt;
}
