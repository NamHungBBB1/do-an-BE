package com.doan.game.repository;

import com.doan.game.entity.Credential;
import com.doan.game.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    /** Đường vào cụ thể của một tài khoản (mật khẩu, hoặc Google sau khi gộp). */
    Optional<Credential> findByAccount_IdAndProvider(UUID accountId, AuthProvider provider);

    /** Subject là khoá thật của nhà cung cấp, KHÔNG phải email. */
    Optional<Credential> findByProviderAndSubject(AuthProvider provider, String subject);
}
