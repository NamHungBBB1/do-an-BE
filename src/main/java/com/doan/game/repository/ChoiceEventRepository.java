package com.doan.game.repository;

import com.doan.game.entity.ChoiceEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChoiceEventRepository extends JpaRepository<ChoiceEvent, UUID> {

    /** Dashboard đợt 2: receivedAt lớn nhất của từng chương trong lần chơi hiện tại (Object[]: chapter, at). */
    @Query("select c.chapter, max(c.receivedAt) from ChoiceEvent c "
            + "where c.slot.id = :slotId and c.attemptIndex = :attemptIndex group by c.chapter")
    List<Object[]> maxReceivedAtByChapter(@Param("slotId") UUID slotId,
                                           @Param("attemptIndex") int attemptIndex);

    /** Tổng số dòng ChoiceEvent của một (slot, chương, lần chơi) — dùng test / chống ghi trùng. */
    long countBySlot_IdAndChapterAndAttemptIndex(UUID slotId, Integer chapter, int attemptIndex);

    /** Test: đọc lại từng dòng của một (slot, chương, lần chơi) để so effects JSON / buildVersion. */
    List<ChoiceEvent> findBySlot_IdAndChapterAndAttemptIndex(UUID slotId, Integer chapter, int attemptIndex);
}
