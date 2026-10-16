package com.doan.game.repository;

import com.doan.game.entity.QuizResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizResultRepository extends JpaRepository<QuizResult, UUID> {

    /** Một em một bài (UNIQUE quiz, slot) — chốt 8005 và chia pending / completed. */
    Optional<QuizResult> findByQuiz_IdAndSlot_Id(UUID quizId, UUID slotId);

    List<QuizResult> findByQuiz_Id(UUID quizId);

    /** Bài của một em mọi đề — completed mới nhất trước. */
    List<QuizResult> findBySlot_IdOrderBySubmittedAtDesc(UUID slotId);

    /** submittedCount từng đề một lần (không N+1): groupId → (quizId, số bài). */
    @Query("select r.quiz.id, count(r) from QuizResult r where r.quiz.group.id = :groupId group by r.quiz.id")
    List<Object[]> countSubmittedByQuizInGroup(@Param("groupId") UUID groupId);

    /** Báo cáo nhóm: số bài và trung bình điểm theo TỪNG slot của lớp (một câu, không N+1). */
    @Query("select r.slot.id, count(r), avg(r.score) from QuizResult r "
            + "where r.quiz.group.id = :groupId group by r.slot.id")
    List<Object[]> statsBySlotInGroup(@Param("groupId") UUID groupId);

    /** Tổng kết nhóm: tổng lượt nộp và trung bình trên MỌI lượt nộp — không nhóm thì vẫn một dòng [0, null]. */
    @Query("select count(r), avg(r.score) from QuizResult r where r.quiz.group.id = :groupId")
    List<Object[]> statsInGroup(@Param("groupId") UUID groupId);
}
