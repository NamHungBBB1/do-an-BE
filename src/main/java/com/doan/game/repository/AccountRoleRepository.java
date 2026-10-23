package com.doan.game.repository;

import com.doan.game.entity.AccountRole;
import com.doan.game.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface AccountRoleRepository extends JpaRepository<AccountRole, UUID> {

    /** Vai nội bộ đang có — nguồn của scope ADMIN trong token. */
    List<AccountRole> findByAccount_Id(UUID accountId);

    boolean existsByAccount_IdAndRole(UUID accountId, Role role);

    /** Vai nội bộ (Studio + admin). Hưng chốt 23/10: tài khoản giữ vai này là riêng, không phải khách hàng. */
    Set<Role> INTERNAL_ROLES = Set.of(Role.ADMIN, Role.GAME_EDITOR, Role.GAME_REVIEWER, Role.GAME_MANAGER);

    boolean existsByAccount_IdAndRoleIn(UUID accountId, Collection<Role> roles);

    /** Tài khoản nội bộ: không mua gói, không nhận gói, không mở nhóm (3025). */
    default boolean isInternal(UUID accountId) {
        return existsByAccount_IdAndRoleIn(accountId, INTERNAL_ROLES);
    }

    /** Dòng cụ thể để thu vai (AdminServiceImpl.revokeRole, 15/10). */
    Optional<AccountRole> findByAccount_IdAndRole(UUID accountId, Role role);

    /** Mọi dòng vai kèm tài khoản trong MỘT truy vấn (join fetch), xếp theo email rồi vai: danh sách nhân sự nội bộ (16/10). */
    @Query("select r from AccountRole r join fetch r.account a order by a.email, r.role")
    List<AccountRole> findAllWithAccountOrderByEmail();
}
