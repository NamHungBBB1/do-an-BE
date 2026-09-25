package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một câu hỏi trong kho dùng chung để giáo viên chọn. Trước 25/09 không hề tồn tại:
 * Quiz.questionIds trỏ vào hư không. Cột standard ghi câu hỏi đo theo khung nào, source ghi nó lấy
 * từ đâu — để nghiên cứu nói được rằng câu hỏi không do nhóm tự nghĩ ra.
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

    @Column(name = "stem")
    private String stem;

    @Column(name = "options")
    private String options;

    @Column(name = "correct_index")
    private Integer correctIndex;

    @Column(name = "standard", length = 80)
    private String standard;

    @Column(name = "source", length = 160)
    private String source;

    @Column(name = "created_at")
    private Instant createdAt;
}
