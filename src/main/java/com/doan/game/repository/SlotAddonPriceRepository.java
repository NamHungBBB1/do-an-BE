package com.doan.game.repository;

import com.doan.game.entity.SlotAddonPrice;
import com.doan.game.enums.PlanKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SlotAddonPriceRepository extends JpaRepository<SlotAddonPrice, UUID> {

    /** Chưa có dòng = admin chưa đặt giá pack → mọi lượt mua trả 4002. */
    Optional<SlotAddonPrice> findByKind(PlanKind kind);
}
