package com.doan.game.repository;

import com.doan.game.entity.ReleaseChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Có ruột (09/10, API nội dung game): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface ReleaseChapterRepository extends JpaRepository<ReleaseChapter, UUID> {

    Optional<ReleaseChapter> findByRelease_IdAndChapter_Code(UUID releaseId, String code);

    List<ReleaseChapter> findByRelease_IdOrderByChapter_ChapterNumberAsc(UUID releaseId);
}
