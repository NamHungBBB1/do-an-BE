package com.doan.game.repository;

import com.doan.game.entity.ReleaseChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReleaseChapterRepository extends JpaRepository<ReleaseChapter, UUID> {
}
