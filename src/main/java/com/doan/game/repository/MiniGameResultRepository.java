package com.doan.game.repository;

import com.doan.game.entity.MiniGameResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MiniGameResultRepository extends JpaRepository<MiniGameResult, UUID> {

    /** Dashboard đợt 2: receivedAt lớn nhất của từng chương trong lần chơi hiện tại (Object[]: chapter, at). */
    @Query("select m.chapter, max(m.receivedAt) from MiniGameResult m "
            + "where m.slot.id = :slotId and m.attemptIndex = :attemptIndex group by m.chapter")
    List<Object[]> maxReceivedAtByChapter(@Param("slotId") UUID slotId,
                                           @Param("attemptIndex") int attemptIndex);

    long countBySlot_IdAndChapterAndAttemptIndex(UUID slotId, Integer chapter, int attemptIndex);

    /** Test: đọc lại từng dòng để so correct / concept / buildVersion của một lần chơi. */
    List<MiniGameResult> findBySlot_IdAndChapterAndAttemptIndex(UUID slotId, Integer chapter, int attemptIndex);
}
