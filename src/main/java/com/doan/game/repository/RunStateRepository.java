package com.doan.game.repository;

import com.doan.game.entity.RunState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RunStateRepository extends JpaRepository<RunState, UUID> {

    /** Một slot đúng một dòng RunState (UNIQUE slot_id) — trỏ nhanh từ token SLOT. */
    Optional<RunState> findBySlot_Id(UUID slotId);
}
