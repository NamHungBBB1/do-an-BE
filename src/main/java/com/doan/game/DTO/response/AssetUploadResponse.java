package com.doan.game.DTO.response;

/** Kết quả tải lên: duplicate = true khi tệp đã có trong kho (không lưu thêm, trả bản cũ — nhãn cũ giữ nguyên). */
public record AssetUploadResponse(AssetResponse asset, boolean duplicate) {
}
