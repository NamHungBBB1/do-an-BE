package com.doan.game.repository;

import com.doan.game.entity.DailySummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DailySummaryRepository extends JpaRepository<DailySummary, UUID> {

    /** Khoá của một bài (19/11): (slot, kind, chương, lần chơi) — enqueue gọi lại vô hại. */
    Optional<DailySummary> findBySlot_IdAndKindAndChapterAndAttemptIndex(UUID slotId, String kind, Integer chapter,
                                                                         Integer attemptIndex);

    /** Mọi bài của một em kèm việc AI (một câu, không N+1) — báo cáo slot ghép theo khoá. */
    @Query("select s from DailySummary s left join fetch s.aiJob where s.slot.id = :slotId")
    List<DailySummary> findBySlotWithJob(@Param("slotId") UUID slotId);

    /** Việc AI xong / hỏng: mọi bài đang trỏ tới việc đó. */
    List<DailySummary> findByAiJob_Id(UUID aiJobId);
}
