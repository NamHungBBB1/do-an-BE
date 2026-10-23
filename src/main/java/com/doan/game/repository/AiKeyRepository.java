package com.doan.game.repository;

import com.doan.game.entity.AiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AiKeyRepository extends JpaRepository<AiKey, UUID> {

    /** Có ruột (24/10, khoá AI): alias là tên gợi nhớ duy nhất, không phân biệt hoa thường. */
    boolean existsByAliasIgnoreCase(String alias);
}
