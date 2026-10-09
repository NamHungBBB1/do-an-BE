package com.doan.game.repository;

import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LearnerSlotRepository extends JpaRepository<LearnerSlot, UUID> {

    /**
     * Trẻ gõ mã, luôn viết hoa trước khi tra. Nạp kèm nhóm: loginSlot chạy NGOÀI transaction (để bộ
     * đếm sai PIN không bị rollback) nên không lazy-load nhóm được, mà response cần groupContext.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "group")
    Optional<LearnerSlot> findByCode(String code);

    /** Sinh mã slot: kiểm TRƯỚC khi chèn — chèn rồi bắt lỗi thì session Hibernate hỏng (xem openSlot). */
    boolean existsByCode(String code);

    /** Chỗ trống = slotLimit − slot ACTIVE; ARCHIVED / WIPED không ăn chỗ. */
    long countByGroup_IdAndStatus(UUID groupId, SlotStatus status);

    /** Slot của một nhóm, cũ đến mới — FE danh sách theo thứ tự mở. */
    java.util.List<LearnerSlot> findByGroup_IdOrderByCreatedAtAsc(UUID groupId);

    java.util.List<LearnerSlot> findByGroup_IdAndStatus(UUID groupId, SlotStatus status);

    /**
     * Sai PIN: tăng bộ đếm bằng MỘT câu UPDATE (rà soát 09/10, G-02). Trước đây đọc → +1 → save entity
     * detached: 50 request song song cùng đọc 0, cùng ghi 1, khoá 15 phút không bao giờ đóng; save cả
     * dòng còn đè ngược status / pinHash vừa được người lớn đổi (G-04). @Transactional riêng vì loginSlot
     * cố ý không có transaction.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LearnerSlot s set "
            + "s.failedAttempts = case when coalesce(s.failedAttempts, 0) + 1 >= :max then 0 "
            + "else coalesce(s.failedAttempts, 0) + 1 end, "
            + "s.lockedUntil = case when coalesce(s.failedAttempts, 0) + 1 >= :max then :lockUntil "
            + "else s.lockedUntil end "
            + "where s.id = :id")
    int recordFailedPin(@Param("id") UUID id, @Param("max") int max, @Param("lockUntil") Instant lockUntil);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LearnerSlot s set s.failedAttempts = 0, s.lockedUntil = null where s.id = :id")
    int resetFailedPin(@Param("id") UUID id);

    /**
     * Đóng nhóm: mọi slot ACTIVE → ARCHIVED bằng một câu, không nạp 40 entity rồi save từng dòng (save cả
     * dòng từ snapshot đầu transaction là đè ngược slot vừa bị xoá sạch song song — G-04). Chạy trong
     * transaction của closeGroup; flush trước để closedAt của nhóm không bị clear mất.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update LearnerSlot s set s.status = :archived, s.archivedAt = :now "
            + "where s.group.id = :groupId and s.status = :active")
    int archiveActiveSlots(@Param("groupId") UUID groupId, @Param("now") Instant now,
                           @Param("active") SlotStatus active, @Param("archived") SlotStatus archived);

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
