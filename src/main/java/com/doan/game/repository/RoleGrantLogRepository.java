package com.doan.game.repository;

import com.doan.game.entity.RoleGrantLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoleGrantLogRepository extends JpaRepository<RoleGrantLog, UUID> {

    /** Lịch sử cấp / thu của một tài khoản, cũ trước — AdminRoleFlowTest, sau này trang admin (15/10). */
    List<RoleGrantLog> findByTarget_IdOrderByCreatedAtAsc(UUID targetId);
}
