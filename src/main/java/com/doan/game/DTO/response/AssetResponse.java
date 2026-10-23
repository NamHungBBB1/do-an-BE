package com.doan.game.DTO.response;

import com.doan.game.enums.AssetKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Một tệp trong kho (24/10, đợt 2a). status do BE tính từ JSON nháp + bản phát hành: IN_USE (bản đang chạy), NEW (chỉ trong
 * nháp), OLD (chỉ còn ở bản trước), ORPHAN (chưa JSON nào dùng). registered = false: tệp JSON đang trỏ tới nhưng chưa có phiếu
 * trong bảng Asset (123 tệp chuyển nhà 22/10 chưa đăng ký) — xem được, chưa sửa / xoá được. ref là chuỗi dán vào JSON chương.
 */
public record AssetResponse(UUID id, boolean registered, AssetKind kind, String ref, String url, Long sizeBytes,
                            Integer width, Integer height, String label, String chapterCode, String uploadedByName,
                            Instant createdAt, String status, List<AssetUsageResponse> usage) {
}
