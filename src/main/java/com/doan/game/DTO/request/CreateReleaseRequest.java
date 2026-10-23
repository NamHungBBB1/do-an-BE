package com.doan.game.DTO.request;

/**
 * Phát hành (PR C, 23/10): gom các nháp ĐÃ DUYỆT; chương không có nháp duyệt giữ nội dung bản hiện tại.
 * version tuỳ chọn — BE tự tính từ loại thay đổi cao nhất; gửi thì phải khớp (6004). note tuỳ chọn.
 */
public record CreateReleaseRequest(String version, String note) {
}
