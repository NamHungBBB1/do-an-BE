package com.doan.game.DTO.request;

/** Sửa nhãn / chương gợi ý của một tệp — không bao giờ sửa tệp (đổi ảnh = tải tệp mới). */
public record UpdateAssetRequest(String label, String chapterCode) {
}
