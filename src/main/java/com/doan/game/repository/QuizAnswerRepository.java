package com.doan.game.repository;

import com.doan.game.entity.QuizAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuizAnswerRepository extends JpaRepository<QuizAnswer, UUID> {

    /** Từng câu của một bài nộp — màn giáo viên xem chi tiết một em. */
    List<QuizAnswer> findByQuizResult_Id(UUID quizResultId);
}
