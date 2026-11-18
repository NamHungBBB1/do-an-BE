package com.doan.game.repository;

import com.doan.game.entity.SlotAddon;
import com.doan.game.enums.PlanKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SlotAddonRepository extends JpaRepository<SlotAddon, UUID> {

    /**
     * Tổng slot add-on của một ví. 18/11/2026: add-on VĨNH VIỄN như gói gốc — không lọc theo hạn,
     * không truyền ngày (trước đây so {@code expiresOn >= today}).
     */
    @Query("select coalesce(sum(a.slots), 0) from SlotAddon a "
            + "where a.account.id = :accountId and a.kind = :kind")
    long sumSlots(@Param("accountId") UUID accountId, @Param("kind") PlanKind kind);

    /** Lịch sử add-on của một tài khoản — màn admin và mail biên nhận. */
    List<SlotAddon> findByAccount_IdOrderByCreatedAtDesc(UUID accountId);

    /** Webhook gọi lại: giao dịch này đã cấp add-on chưa (chống cấp hai lần). */
    boolean existsByTransaction_Id(UUID transactionId);
}
