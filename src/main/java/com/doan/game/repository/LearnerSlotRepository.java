package com.doan.game.repository;

import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LearnerSlotRepository extends JpaRepository<LearnerSlot, UUID> {

    /** Trẻ gõ mã, luôn viết hoa trước khi tra. */
    Optional<LearnerSlot> findByCode(String code);

    /** Chỗ trống = slotLimit − slot ACTIVE; ARCHIVED / WIPED không ăn chỗ. */
    long countByGroup_IdAndStatus(UUID groupId, SlotStatus status);
}
