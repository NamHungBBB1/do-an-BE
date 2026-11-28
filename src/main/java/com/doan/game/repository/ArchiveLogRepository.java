package com.doan.game.repository;

import com.doan.game.entity.ArchiveLog;
import com.doan.game.enums.ArchiveAction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Có ruột (27/11, vòng đời lưu trữ): bộ sinh khung giữ nguyên tệp này. Nhật ký chỉ thêm. */
@Repository
public interface ArchiveLogRepository extends JpaRepository<ArchiveLog, UUID> {

    @Query("select l from ArchiveLog l left join fetch l.actor order by l.createdAt desc, l.id")
    List<ArchiveLog> findLatest(Pageable page);

    boolean existsByAction(ArchiveAction action);
}
