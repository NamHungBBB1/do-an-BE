package com.doan.game.repository;

import com.doan.game.entity.Entitlement;
import com.doan.game.enums.PlanKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Có ruột (02/10): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface EntitlementRepository extends JpaRepository<Entitlement, UUID> {

    /** Gói mới nhất của một loại: gia hạn nối tiếp từ expiresOn của nó. */
    Optional<Entitlement> findTopByAccount_IdAndKindOrderByExpiresOnDesc(UUID accountId, PlanKind kind);

    /**
     * Loại gói còn hạn hôm nay. Ngày so theo GIỜ VIỆT NAM do service truyền vào, không lấy trong
     * câu truy vấn — nếu lấy NOW() thì máy chủ chạy giờ UTC sẽ lệch một ngày vào đêm.
     *
     * distinct vì một tài khoản có thể còn nhiều dòng cùng loại (mua gia hạn nhiều lần).
     */
    @Query("select distinct e.kind from Entitlement e "
            + "where e.account.id = :id and e.startsOn <= :homNay and e.expiresOn >= :homNay")
    Set<PlanKind> findActivePlanKinds(@Param("id") UUID accountId, @Param("homNay") LocalDate today);

    /**
     * Lịch sử gói của một tài khoản, hạn mới nhất đứng trước. CÓ cả gói hết hạn: người dùng cần
     * thấy đã từng mua gì, còn hạn thì /api/auth/me đã trả (plans).
     */
    List<Entitlement> findByAccount_IdOrderByExpiresOnDesc(UUID accountId);
}
