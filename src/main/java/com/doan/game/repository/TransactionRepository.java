package com.doan.game.repository;

import com.doan.game.entity.Transaction;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.TransactionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Có ruột (02/10): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByOrderCode(Long orderCode);

    /**
     * Khoá hàng khi ghi nhận đã trả: webhook và returnUrl có thể cùng lúc thấy PENDING; khoá để
     * cái đến sau phải chờ rồi thấy PAID, thay vì cả hai cùng cấp gói.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.orderCode = :orderCode")
    Optional<Transaction> lockByOrderCode(@Param("orderCode") Long orderCode);

    List<Transaction> findByStatusAndCreatedAtBetween(TransactionStatus status, Instant from, Instant to);

    Page<Transaction> findAllByOrderByCreatedAtDesc(Pageable p);

    Page<Transaction> findByStatusOrderByCreatedAtDesc(TransactionStatus status, Pageable p);

    /** Lịch sử giao dịch của chính người dùng, mới nhất trước. */
    Page<Transaction> findByAccount_IdOrderByCreatedAtDesc(UUID accountId, Pageable p);

    /** Đơn còn chờ cùng gói, QR còn hạn: bấm Mua lại thì trả đúng đơn này (idempotent). */
    Optional<Transaction> findFirstByAccount_IdAndKindAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID accountId, PlanKind kind, TransactionStatus status, Instant after);
}
