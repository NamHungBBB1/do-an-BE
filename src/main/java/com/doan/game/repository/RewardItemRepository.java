package com.doan.game.repository;

import com.doan.game.entity.RewardItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RewardItemRepository extends JpaRepository<RewardItem, UUID> {
}
