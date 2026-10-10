package com.doan.game.repository;

import com.doan.game.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** Email không phân biệt hoa thường: "A@b.vn" và "a@b.vn" là một tài khoản. */
    Optional<Account> findByEmailIgnoreCase(String email);

    /**
     * Khoá dòng tài khoản (P-02 / P-03, 10/10): tạo đơn và cấp gói cho CÙNG một tài khoản chạy tuần tự —
     * bấm Mua hai tab không ra hai đơn PENDING, hai webhook / webhook + admin cấp không ra hai gói chồng kỳ hạn.
     * Thứ tự khoá ở mọi đường: Transaction (lockByOrderCode) rồi mới Account — không deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(@Param("id") UUID id);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Sai mật khẩu: tăng bộ đếm bằng MỘT câu UPDATE trong DB — N request song song không thể cùng đọc 0
     * rồi cùng ghi 1 (rà soát 09/10, S-04). Đủ max thì về 0 và khoá tới lockUntil.
     * @Transactional riêng vì nơi gọi (login, changePassword) cố ý không có transaction: câu này tự
     * commit, bộ đếm sống qua lỗi ném ngay sau đó.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Account a set "
            + "a.failedAttempts = case when coalesce(a.failedAttempts, 0) + 1 >= :max then 0 "
            + "else coalesce(a.failedAttempts, 0) + 1 end, "
            + "a.lockedUntil = case when coalesce(a.failedAttempts, 0) + 1 >= :max then :lockUntil "
            + "else a.lockedUntil end "
            + "where a.id = :id")
    int recordFailedPassword(@Param("id") UUID id, @Param("max") int max, @Param("lockUntil") Instant lockUntil);

    /** Đăng nhập đúng: xoá bộ đếm bằng UPDATE đúng hai cột, không merge cả dòng (tránh đè dữ liệu song song). */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Account a set a.failedAttempts = 0, a.lockedUntil = null where a.id = :id")
    int resetFailedPassword(@Param("id") UUID id);
}
