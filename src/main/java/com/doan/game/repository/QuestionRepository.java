package com.doan.game.repository;

import com.doan.game.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    /** Nhập kho upsert theo externalId — khoá không cho nhân đôi khi import lại. */
    Optional<Question> findByExternalId(String externalId);

    /** Bộ hậu trắc của chương N, chưa rút, xếp theo externalId — chụp nguyên bộ vào đề lúc tạo. */
    List<Question> findByChapterAndRetiredAtIsNullOrderByExternalIdAsc(Integer chapter);

    /** Kho chung giáo viên chọn: chưa rút, không phải câu hậu trắc, không phải câu của giáo viên nào (16/11 v3). */
    List<Question> findByRetiredAtIsNullAndChapterIsNullAndOwnerIsNull();

    /** "Câu của tôi" (quiz v3): câu chưa rút của một giáo viên, mới nhất trước — cũng là tập so trùng khi lưu lô. */
    List<Question> findByOwner_IdAndRetiredAtIsNullOrderByCreatedAtDesc(UUID ownerId);
    /**
     * Báo cáo người lớn (19/11): số câu hậu trắc còn sống theo chương (Object[]: chapter, count) — cùng điều kiện với
     * chỗ tự tạo đề hậu trắc (findByChapterAndRetiredAtIsNull…), để "chương không có hậu trắc" khớp với "không bao giờ có đề".
     */
    @Query("select q.chapter, count(q) from Question q where q.chapter in :chapters and q.retiredAt is null "
            + "group by q.chapter")
    List<Object[]> countLiveByChapter(@Param("chapters") Collection<Integer> chapters);
}
