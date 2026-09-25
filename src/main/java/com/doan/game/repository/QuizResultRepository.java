package com.doan.game.repository;

import com.doan.game.entity.QuizResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface QuizResultRepository extends JpaRepository<QuizResult, UUID> {
}
