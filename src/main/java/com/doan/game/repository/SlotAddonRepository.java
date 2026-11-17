package com.doan.game.repository;

import com.doan.game.entity.SlotAddon;
import com.doan.game.enums.PlanKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface SlotAddonRepository extends JpaRepository<SlotAddon, UUID> {

    /**
     * Tổng slot add-on còn hạn của một ví. Ngày so theo GIỜ VIỆT NAM do service truyền vào (cùng luật
     * với gói), không lấy NOW() trong câu truy vấn — máy chủ chạy UTC sẽ lệch một ngày vào đêm.
     */
    @Query("select coalesce(sum(a.slots), 0) from SlotAddon a "
            + "where a.account.id = :accountId and a.kind = :kind and a.expiresOn >= :today")
    long sumActiveSlots(@Param("accountId") UUID accountId, @Param("kind") PlanKind kind,
                        @Param("today") LocalDate today);

    /** Lịch sử add-on của một tài khoản — màn admin và kiểm trần khi mua pack tiếp theo. */
    List<SlotAddon> findByAccount_IdOrderByCreatedAtDesc(UUID accountId);

    /** Webhook gọi lại: giao dịch này đã cấp add-on chưa (chống cấp hai lần). */
    boolean existsByTransaction_Id(UUID transactionId);
}
