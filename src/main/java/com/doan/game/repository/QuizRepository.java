package com.doan.game.repository;

import com.doan.game.entity.Quiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID> {

    /** Đề của một lớp, cả hai kiểu — thứ tự "mới nhất trước" sắp trong service (nháp không có publishedAt). */
    List<Quiz> findByGroup_Id(UUID groupId);

    /** Một lớp mỗi chương đúng một đề hậu trắc (UNIQUE group, kind, chapter) — hai em xong chương cùng lúc: bên sau đọc lại. */
    Optional<Quiz> findByGroup_IdAndKindAndChapter(UUID groupId, com.doan.game.enums.QuizKind kind, Integer chapter);

    /** Đề đã publish của lớp — "assigned" trong báo cáo / kết quả (cả hậu trắc lẫn đề giáo viên). */
    long countByGroup_IdAndPublishedAtIsNotNull(UUID groupId);

    /** 17/10 (Studio kho câu hỏi): câu đã nằm trong đề đã phát thì khoá nội dung (8009). questionIds là JSON text, tìm chuỗi id. */
    boolean existsByPublishedAtIsNotNullAndQuestionIdsContaining(String questionId);
}
