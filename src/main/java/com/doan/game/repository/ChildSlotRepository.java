package com.doan.game.repository;

import com.doan.game.entity.ChildSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ChildSlotRepository extends JpaRepository<ChildSlot, UUID> {
}
