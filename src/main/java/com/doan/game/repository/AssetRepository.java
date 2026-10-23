package com.doan.game.repository;

import com.doan.game.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetRepository extends JpaRepository<Asset, UUID> {

    /** Có ruột (24/10, kho ảnh đợt 2a): tên tệp = hash nội dung WebP, UNIQUE. */
    Optional<Asset> findBySha256(String sha256);

    /** Hash tệp gốc người dùng tải lên — bắt trùng cả khi WebP ra byte khác (đổi phiên bản cwebp). */
    Optional<Asset> findFirstBySourceSha256(String sourceSha256);
}
