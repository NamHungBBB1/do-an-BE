package com.doan.game.repository;

import com.doan.game.entity.RoleGrantLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoleGrantLogRepository extends JpaRepository<RoleGrantLog, UUID> {

    /** Lịch sử cấp / thu của một tài khoản, cũ trước — AdminRoleFlowTest, sau này trang admin (15/10). */
    List<RoleGrantLog> findByTarget_IdOrderByCreatedAtAsc(UUID targetId);

    /** Sổ toàn hệ thống, mới nhất trước, kèm actor + target trong cùng truy vấn (GET /api/admin/roles/log, 16/10). */
    @EntityGraph(attributePaths = {"actor", "target"})
    List<RoleGrantLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Sổ của một tài khoản (target), mới nhất trước. */
    @EntityGraph(attributePaths = {"actor", "target"})
    List<RoleGrantLog> findByTarget_IdOrderByCreatedAtDesc(UUID targetId, Pageable pageable);
}
