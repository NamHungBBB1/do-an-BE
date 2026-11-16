package com.doan.game.repository;

import com.doan.game.entity.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {

    /** Mọi lượt của một em ở một đề, lượt 1 trước — mở đề: còn lượt mở thì trả lại, không thì tạo lượt kế. */
    List<QuizAttempt> findByQuiz_IdAndSlot_IdOrderByAttemptNoAsc(UUID quizId, UUID slotId);

    /** Lượt đầu của cả lớp ở một đề (một câu) — kết quả giáo viên đọc từ lượt đầu (lần nộp đầu là kết quả chính thức). */
    List<QuizAttempt> findByQuiz_IdAndAttemptNo(UUID quizId, Integer attemptNo);

    Optional<QuizAttempt> findByQuiz_IdAndSlot_IdAndAttemptNo(UUID quizId, UUID slotId, Integer attemptNo);

    /** Slot có lượt quá hạn chưa chốt — chỉ lấy id để khoá slot TRƯỚC khi nạp lượt (nạp trước thì entity cũ trong session). */
    @Query("select distinct a.slot.id from QuizAttempt a where a.quiz.id = :quizId "
            + "and a.submittedAt is null and a.deadlineAt < :cutoff")
    List<UUID> findSlotIdsWithExpiredOpen(@Param("quizId") UUID quizId, @Param("cutoff") Instant cutoff);

    /** Lượt quá hạn chưa chốt của một đề (gọi sau khi đã khoá slot). */
    List<QuizAttempt> findByQuiz_IdAndSubmittedAtIsNullAndDeadlineAtBefore(UUID quizId, Instant cutoff);

    /** Như trên nhưng cho mọi đề của một nhóm — báo cáo, danh sách đề, danh sách của em chốt lười trước khi đọc. */
    @Query("select distinct a.slot.id from QuizAttempt a where a.quiz.group.id = :groupId "
            + "and a.submittedAt is null and a.deadlineAt < :cutoff")
    List<UUID> findSlotIdsWithExpiredOpenInGroup(@Param("groupId") UUID groupId, @Param("cutoff") Instant cutoff);

    List<QuizAttempt> findByQuiz_Group_IdAndSubmittedAtIsNullAndDeadlineAtBefore(UUID groupId, Instant cutoff);

    /** Mọi lượt của một em (một câu) — danh sách của em lấy lượt mới nhất từng đề (attemptId). */
    List<QuizAttempt> findBySlot_IdOrderByAttemptNoAsc(UUID slotId);

    /** Đường nộp cũ: hậu trắc em đã mở lượt làm thì không nộp đường cũ nữa (8010). */
    boolean existsByQuiz_IdAndSlot_Id(UUID quizId, UUID slotId);
}
