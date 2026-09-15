package com.doan.game.auth.repository;

import com.doan.game.auth.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Tra theo BĂM của token trong link, không phải token gốc. */
    Optional<Account> findByVerifyTokenHash(String verifyTokenHash);
}
