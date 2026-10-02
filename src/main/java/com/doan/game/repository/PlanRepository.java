package com.doan.game.repository;

import com.doan.game.entity.Plan;
import com.doan.game.enums.PlanKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Có ruột (02/10): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface PlanRepository extends JpaRepository<Plan, UUID> {

    Optional<Plan> findByKind(PlanKind kind);
}
