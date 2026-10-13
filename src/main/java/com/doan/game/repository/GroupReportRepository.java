package com.doan.game.repository;

import com.doan.game.entity.GroupReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupReportRepository extends JpaRepository<GroupReport, UUID> {

    /** Bản đóng băng của nhóm (một nhóm chỉ đóng một lần — 5002); đọc nhóm ĐÃ ĐÓNG lấy bản này. */
    Optional<GroupReport> findFirstByGroup_IdOrderByFrozenAtDesc(UUID groupId);
}
