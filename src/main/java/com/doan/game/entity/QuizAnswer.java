package com.doan.game.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Từng câu trả lời: để cập nhật mức nắm khái niệm và thống kê câu nào cả lớp sai nhiều. Ràng buộc:
 * UNIQUE(quizResultId, questionId)
 */
@Entity
@Table(name = "quiz_answer", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"quiz_result_id", "question_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class QuizAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** QuizResult. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_result_id", nullable = false)
    private QuizResult quizResult;

    /** Question. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "chosen_index")
    private Integer chosenIndex;

    @Column(name = "correct", nullable = false, columnDefinition = "boolean default false not null")
    private boolean correct;
}
