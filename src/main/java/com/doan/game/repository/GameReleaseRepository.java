package com.doan.game.repository;

import com.doan.game.entity.GameRelease;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<GameRelease> findAllByOrderByReleasedAtDesc();
}
