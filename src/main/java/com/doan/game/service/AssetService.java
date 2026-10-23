package com.doan.game.service;

import com.doan.game.DTO.request.UpdateAssetRequest;
import com.doan.game.DTO.response.AssetResponse;
import com.doan.game.DTO.response.AssetUploadResponse;

import java.util.List;
import java.util.UUID;

/**
 * Kho ảnh trong Studio (24/10, đợt 2a — spec build/spec-kho-anh-giong-2026-10-24.md mục 3). Cổng /api/studio mở cho 4 vai;
 * ghi (tải lên, sửa nhãn, xoá) = ADMIN / GAME_EDITOR / GAME_MANAGER, Reviewer chỉ xem.
 */
public interface AssetService {

    /** Mọi tệp đã đăng ký + tệp JSON đang trỏ mà chưa đăng ký; lọc kind (IMAGE / AUDIO), chapter (CH04), status, q (nhãn). */
    List<AssetResponse> list(String kind, String chapter, String status, String q);

    AssetResponse get(UUID id);

    /**
     * PNG / JPEG → WebP (rộng ≤ 1920, chất lượng 80, giữ trong suốt — cùng thông số build_assets.py); WebP giữ nguyên.
     * Trùng tệp gốc hoặc trùng WebP → trả bản đã có (duplicate = true). Sai loại / quá 5 MB → 6008.
     */
    AssetUploadResponse upload(UUID callerId, String filename, byte[] bytes, String label, String chapterCode);

    AssetResponse update(UUID callerId, UUID id, UpdateAssetRequest req);

    /** Chỉ xoá tệp mồ côi (chưa JSON nào dùng) → khác thì 6009. */
    void delete(UUID callerId, UUID id);
}
