package com.doan.game.repository;

import com.doan.game.entity.VerificationToken;
import com.doan.game.enums.TokenPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    /** Tra bằng HASH, không phải bằng token gốc — token gốc không có trong bảng. */
    Optional<VerificationToken> findByTokenHash(String tokenHash);

    /** Token mới nhất của một mục đích: dùng để chặn gửi lại quá dày (VERIFY_TOO_SOON). */
    Optional<VerificationToken> findTopByAccount_IdAndPurposeOrderByCreatedAtDesc(UUID accountId, TokenPurpose purpose);

    /** Token còn hiệu lực của một mục đích: dùng vô hiệu hết khi đặt lại mật khẩu xong. */
    List<VerificationToken> findByAccount_IdAndPurposeAndUsedAtIsNull(UUID accountId, TokenPurpose purpose);
}
