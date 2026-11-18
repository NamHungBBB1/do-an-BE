package com.doan.game.DTO.request;

/**
 * Mở một nhóm. context FAMILY (cần gói PARENT) hoặc CLASS (cần gói TEACHER).
 *
 * 17/11/2026: hạn mức KHÔNG còn chép vào nhóm. Giáo viên mở nhiều lớp, ví slot nằm trên tài khoản
 * (xem SlotQuotaService); plannedSize là SĨ SỐ lớp do giáo viên đặt, chỉ dùng cho CLASS. Bỏ trống thì
 * lấy bằng số slot còn lại của ví.
 */
public record CreateGroupRequest(String name, String context, Integer plannedSize) {
}
