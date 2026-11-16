package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lượt làm của một em: đề riêng đã xáo (paper = [{q: id câu, o: chỉ số gốc của đáp án ở từng
 * vị trí}]), câu trả lời tự lưu (answers = {vị trí: lựa chọn em thấy}), đồng hồ do BE tính
 * (deadlineAt), điểm. Lần nộp đầu vẫn ghi QuizResult / QuizAnswer (chỉ số gốc) — kết quả chính
 * thức. leaveCount = số lần rời trang máy trẻ tự báo, chỉ để tham khảo. Ràng buộc: UNIQUE(quizId,
 * slotId, attemptNo)
 */
@Entity
@Table(name = "quiz_attempt", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"quiz_id", "slot_id", "attempt_no"})
})
@Getter
@Setter
@NoArgsConstructor
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Quiz. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    /** LearnerSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private LearnerSlot slot;

    @Column(name = "attempt_no")
    private Integer attemptNo;

    @Column(name = "paper", columnDefinition = "text")
    private String paper;

    @Column(name = "answers", columnDefinition = "text")
    private String answers;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "auto_submitted", nullable = false, columnDefinition = "boolean default false not null")
    private boolean autoSubmitted;

    @Column(name = "score")
    private Integer score;

    @Column(name = "leave_count")
    private Integer leaveCount;
}
