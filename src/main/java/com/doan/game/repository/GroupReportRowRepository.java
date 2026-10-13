package com.doan.game.repository;

import com.doan.game.entity.GroupReportRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GroupReportRowRepository extends JpaRepository<GroupReportRow, UUID> {

    /** Dòng đóng băng của một bản báo cáo — số đọc ở đây, danh tính ghép sống từ LearnerSlot. */
    List<GroupReportRow> findByReport_Id(UUID reportId);
}
