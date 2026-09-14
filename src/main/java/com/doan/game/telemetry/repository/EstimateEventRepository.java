package com.doan.game.telemetry.repository;

import com.doan.game.telemetry.domain.EstimateEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EstimateEventRepository extends JpaRepository<EstimateEvent, UUID> {
    List<EstimateEvent> findBySeedOrderByCommittedAtAsc(String seed);
    long countByPlayerKey(String playerKey);
}
