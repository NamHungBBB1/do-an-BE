package com.doan.game.repository;

import com.doan.game.entity.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Có ruột (09/10, API nội dung game): bộ sinh khung giữ nguyên tệp này. */
@Repository
public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    Optional<Chapter> findByCode(String code);

    List<Chapter> findAllByOrderByChapterNumberAsc();
}
