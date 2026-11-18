package com.doan.game.DTO.response;

/**
 * Ví slot của một tài khoản theo một loại nhóm (GET /api/me/quota, 18/11/2026).
 *
 * context FAMILY = ví phụ huynh (gói PARENT), CLASS = ví giáo viên (gói TEACHER). Gói vĩnh viễn nên
 * KHÔNG còn termStart/termEnd; thay vào đó là reserved (chỗ đã giữ cho lớp đang mở) và free (còn
 * dùng được ngay).
 */
public record QuotaResponse(String context, String plan, int base, int extra, int limit, int used,
                            int reserved, int free, int wiped) {
}
