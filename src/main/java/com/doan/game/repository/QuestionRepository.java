package com.doan.game.repository;

import com.doan.game.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    /** Nhập kho upsert theo externalId — khoá không cho nhân đôi khi import lại. */
    Optional<Question> findByExternalId(String externalId);

    /** Bộ hậu trắc của chương N, chưa rút, xếp theo externalId — chụp nguyên bộ vào đề lúc tạo. */
    List<Question> findByChapterAndRetiredAtIsNullOrderByExternalIdAsc(Integer chapter);

    /** Kho chung giáo viên chọn: chưa rút và không phải câu hậu trắc. */
    List<Question> findByRetiredAtIsNullAndChapterIsNull();
}
