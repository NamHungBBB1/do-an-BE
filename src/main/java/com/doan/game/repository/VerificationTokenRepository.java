package com.doan.game.repository;

import com.doan.game.entity.VerificationToken;
import com.doan.game.enums.TokenPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    /** Token mới nhất của một mục đích: dùng để chặn gửi lại quá dày (VERIFY_TOO_SOON). */
    Optional<VerificationToken> findTopByAccount_IdAndPurposeOrderByCreatedAtDesc(UUID accountId, TokenPurpose purpose);

    /** Token còn hiệu lực của một mục đích: dùng vô hiệu hết khi đặt lại mật khẩu xong. */
    List<VerificationToken> findByAccount_IdAndPurposeAndUsedAtIsNull(UUID accountId, TokenPurpose purpose);

    /**
     * Trừ một lượt đoán mã, nguyên tử: chỉ cộng khi attempts < max, trả số dòng (1 = còn lượt, 0 = mã đã
     * chết). Gọi TRƯỚC khi so BCrypt, ngoài transaction, nên N request song song cũng chỉ được đúng max
     * lượt (rà soát 09/10, S-04).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update VerificationToken t set t.attempts = coalesce(t.attempts, 0) + 1 "
            + "where t.id = :id and coalesce(t.attempts, 0) < :max")
    int consumeAttempt(@Param("id") UUID id, @Param("max") int max);
}
