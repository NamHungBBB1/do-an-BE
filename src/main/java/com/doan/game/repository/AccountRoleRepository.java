package com.doan.game.repository;

import com.doan.game.entity.AccountRole;
import com.doan.game.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AccountRoleRepository extends JpaRepository<AccountRole, UUID> {

    /** Vai nội bộ đang có — nguồn của scope ADMIN trong token. */
    List<AccountRole> findByAccount_Id(UUID accountId);

    boolean existsByAccount_IdAndRole(UUID accountId, Role role);
}
