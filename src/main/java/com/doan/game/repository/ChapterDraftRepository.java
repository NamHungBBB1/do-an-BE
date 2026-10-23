package com.doan.game.repository;

import com.doan.game.entity.ChapterDraft;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Có ruột (09/10, API nội dung game): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface ChapterDraftRepository extends JpaRepository<ChapterDraft, UUID> {

    /**
     * Bản nháp mới nhất của một chương — theo revision, không theo updatedAt (Clock cố định trong test làm hoà).
     * Từ PR C (23/10) một chương có thể có nhiều dòng: dòng đã RELEASED giữ nguyên, sửa tiếp thì mở dòng mới.
     */
    Optional<ChapterDraft> findTopByChapter_IdOrderByRevisionDesc(UUID chapterId);

    /**
     * Như trên nhưng KHOÁ dòng (SELECT … FOR UPDATE) — dùng ở mọi đường ghi (lưu, gửi, rút, duyệt, phát hành):
     * không có khoá thì phát hành set RELEASED đè mất nội dung Editor vừa lưu (soát 23/10).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChapterDraft> findFirstByChapter_IdOrderByRevisionDesc(UUID chapterId);

    List<ChapterDraft> findByChapter_IdOrderByUpdatedAtDesc(UUID chapterId);
}
