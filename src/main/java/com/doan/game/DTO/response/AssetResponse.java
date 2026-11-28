package com.doan.game.DTO.response;

import com.doan.game.enums.AssetKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Một tệp trong kho (24/10, đợt 2a). status do BE tính từ JSON nháp + bản phát hành: IN_USE (bản đang chạy), NEW (chỉ trong
 * nháp), OLD (chỉ còn ở bản trước), ORPHAN (chưa JSON nào dùng). registered = false: tệp JSON đang trỏ tới nhưng chưa có phiếu
 * trong bảng Asset (123 tệp chuyển nhà 22/10 chưa đăng ký) — xem được, chưa sửa / xoá được. ref là chuỗi dán vào JSON chương.
 * thumbUrl (26/11): hình thu nhỏ rộng 320 cho màn kho ảnh; null với giọng hoặc khi chưa tạo — FE khi đó dùng url.
 * archiveDaysLeft (27/11): số ngày tới lúc tệp mồ côi được đưa đi lưu trữ; null khi tệp không đang đếm hoặc chức năng tắt.
 */
public record AssetResponse(UUID id, boolean registered, AssetKind kind, String ref, String url, Long sizeBytes,
                            Integer width, Integer height, String label, String chapterCode, String uploadedByName,
                            Instant createdAt, String status, List<AssetUsageResponse> usage, String thumbUrl,
                            Integer archiveDaysLeft) {
}
