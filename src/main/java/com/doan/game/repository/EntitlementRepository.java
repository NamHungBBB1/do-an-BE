package com.doan.game.repository;

import com.doan.game.entity.Entitlement;
import com.doan.game.enums.PlanKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Có ruột (02/10): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface EntitlementRepository extends JpaRepository<Entitlement, UUID> {

    /** Gói mới nhất của một loại: gia hạn nối tiếp từ expiresOn của nó. */
    Optional<Entitlement> findTopByAccount_IdAndKindOrderByExpiresOnDesc(UUID accountId, PlanKind kind);
}
