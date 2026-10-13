package com.doan.game.repository;

import com.doan.game.entity.Achievement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AchievementRepository extends JpaRepository<Achievement, UUID> {

    /** Báo cáo slot: huy hiệu mới nhất trước (report.md). */
    List<Achievement> findBySlot_IdOrderByEarnedAtDesc(UUID slotId);
}
