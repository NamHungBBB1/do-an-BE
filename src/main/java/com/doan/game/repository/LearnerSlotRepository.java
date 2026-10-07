package com.doan.game.repository;

import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LearnerSlotRepository extends JpaRepository<LearnerSlot, UUID> {

    /** Trẻ gõ mã, luôn viết hoa trước khi tra. */
    Optional<LearnerSlot> findByCode(String code);

    /** Sinh mã slot: kiểm TRƯỚC khi chèn — chèn rồi bắt lỗi thì session Hibernate hỏng (xem openSlot). */
    boolean existsByCode(String code);

    /** Chỗ trống = slotLimit − slot ACTIVE; ARCHIVED / WIPED không ăn chỗ. */
    long countByGroup_IdAndStatus(UUID groupId, SlotStatus status);

    /** Slot của một nhóm, cũ đến mới — FE danh sách theo thứ tự mở. */
    java.util.List<LearnerSlot> findByGroup_IdOrderByCreatedAtAsc(UUID groupId);

    /** Đóng nhóm: mọi slot ACTIVE của nhóm được đưa sang ARCHIVED một lần. */
    java.util.List<LearnerSlot> findByGroup_IdAndStatus(UUID groupId, SlotStatus status);

    /**
     * Một câu đếm slot ACTIVE cho TẤT cả nhóm của MỘT người gọi (GET /api/groups) — tránh N+1
     * từng nhóm. Lọc owner ngay trong SQL: đếm cả hệ thống rồi lọc ở Java là lãng phí vô ích
     * (Kidz góp ý review PR 2a, 07/10).
     */
    @Query("select s.group.id as groupId, count(s) as used from LearnerSlot s "
            + "where s.status = :status and s.group.owner.id = :ownerId group by s.group.id")
    java.util.List<SlotUsedRow> countByStatusGroupedByGroup(@Param("status") SlotStatus status,
                                                            @Param("ownerId") UUID ownerId);

    /** Dòng kết quả của countByStatusGroupedByGroup — interface projection, không cần class mới. */
    interface SlotUsedRow {
        UUID getGroupId();
        long getUsed();
    }
}
