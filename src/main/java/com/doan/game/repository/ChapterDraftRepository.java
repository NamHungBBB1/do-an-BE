package com.doan.game.repository;

import com.doan.game.entity.ChapterDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Có ruột (09/10, API nội dung game): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface ChapterDraftRepository extends JpaRepository<ChapterDraft, UUID> {

    /** Bản nháp mới nhất của một chương (giai đoạn 1: mỗi chương giữ một bản nháp sống). */
    Optional<ChapterDraft> findTopByChapter_IdOrderByUpdatedAtDesc(UUID chapterId);

    List<ChapterDraft> findByChapter_IdOrderByUpdatedAtDesc(UUID chapterId);
}
