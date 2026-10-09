package com.doan.game.repository;

import com.doan.game.entity.Credential;
import com.doan.game.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    /** Đường vào cụ thể của một tài khoản (mật khẩu, hoặc Google sau khi gộp). */
    Optional<Credential> findByAccount_IdAndProvider(UUID accountId, AuthProvider provider);

    /** Subject là khoá thật của nhà cung cấp, KHÔNG phải email. */
    Optional<Credential> findByProviderAndSubject(AuthProvider provider, String subject);

    /**
     * Ghi lastUsedAt bằng UPDATE một cột. Trước đây login save(c) cả dòng từ bản đã đọc trước khi so
     * BCrypt: đổi mật khẩu commit đúng lúc đó là bị ghi đè lại hash cũ (rà soát 09/10, G-04).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Credential c set c.lastUsedAt = :at where c.id = :id")
    int touchLastUsed(@Param("id") UUID id, @Param("at") Instant at);
}
