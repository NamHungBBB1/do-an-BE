package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kho câu hỏi dùng chung (nhập từ pool công khai, upsert theo externalId, giáo viên chỉ chọn) cộng
 * bộ hậu trắc cố định của từng chương (chapter = N; NULL = kho chung). Câu rút khỏi kho chỉ đặt
 * retiredAt, không xoá, để đề cũ còn nguyên. Đáp án không bao giờ xuống trẻ.
 */
@Entity
@Table(name = "question")
@Getter
@Setter
@NoArgsConstructor
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "pool", length = 40)
    private String pool;

    @Column(name = "stem", columnDefinition = "text")
    private String stem;

    @Column(name = "options", columnDefinition = "text")
    private String options;

    @Column(name = "correct_index")
    private Integer correctIndex;

    @Column(name = "standard", length = 80)
    private String standard;

    @Column(name = "source", length = 160)
    private String source;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "external_id", length = 80, unique = true)
    private String externalId;

    @Column(name = "concept", length = 40)
    private String concept;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "chapter")
    private Integer chapter;
}
