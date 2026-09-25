package com.doan.game.repository;

import com.doan.game.entity.Redemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RedemptionRepository extends JpaRepository<Redemption, UUID> {
}
