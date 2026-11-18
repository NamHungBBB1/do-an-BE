package com.doan.game.repository;

import com.doan.game.entity.ReleaseChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Có ruột (09/10, API nội dung game): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface ReleaseChapterRepository extends JpaRepository<ReleaseChapter, UUID> {

    Optional<ReleaseChapter> findByRelease_IdAndChapter_Code(UUID releaseId, String code);

    /** Lô play trỏ theo SỐ chương (chapter=1…): tìm chương của đúng bản phát hành — 7001 nếu không có. */
    Optional<ReleaseChapter> findByRelease_IdAndChapter_ChapterNumber(UUID releaseId, Integer chapterNumber);

    List<ReleaseChapter> findByRelease_IdOrderByChapter_ChapterNumberAsc(UUID releaseId);
    /** Báo cáo người lớn (19/11): JSON đóng băng của các (bản, chương) bé đã chơi — một câu, kèm số hiệu + số chương. */
    @Query("select rc from ReleaseChapter rc join fetch rc.release r join fetch rc.chapter c "
            + "where r.version in :versions and c.chapterNumber in :chapters")
    List<ReleaseChapter> findPlayed(@Param("versions") Collection<String> versions,
                                    @Param("chapters") Collection<Integer> chapters);
}
