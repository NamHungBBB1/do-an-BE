package com.doan.game.repository;

import com.doan.game.entity.QuizAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface QuizAnswerRepository extends JpaRepository<QuizAnswer, UUID> {

    /** Từng câu của một bài nộp — màn giáo viên xem chi tiết một em. */
    List<QuizAnswer> findByQuizResult_Id(UUID quizResultId);
    /** Báo cáo người lớn (19/11): (quizResultId, concept, correct) của các bài đã nộp, chỉ câu có gắn mã — một câu. */
    @Query("select a.quizResult.id, q.concept, a.correct from QuizAnswer a join a.question q "
            + "where a.quizResult.id in :resultIds and q.concept is not null")
    List<Object[]> conceptAnswers(@Param("resultIds") Collection<UUID> resultIds);
}
