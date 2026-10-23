package com.doan.game.repository;

import com.doan.game.entity.GameRelease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Có ruột (09/10, API nội dung game): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface GameReleaseRepository extends JpaRepository<GameRelease, UUID> {

    Optional<GameRelease> findByVersion(String version);

    /** Con trỏ "bản hiện tại" — service giữ đúng một dòng true. */
    Optional<GameRelease> findFirstByIsCurrentTrue();

    List<GameRelease> findByIsCurrentTrue();

    /**
     * Khoá con trỏ bản hiện tại — phát hành, đổi bản, thu hồi gọi đầu tiên để chạy lần lượt (không ra hai bản current,
     * không hai bản cùng số hiệu). Lần phát hành đầu chưa có dòng để khoá: UNIQUE(version) chặn, handler trả 409.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from GameRelease r where r.isCurrent = true")
    List<GameRelease> lockCurrent();

    List<GameRelease> findAllByOrderByReleasedAtDesc();
}
