package com.doan.game.DTO.response;

/**
 * Ví slot của một tài khoản theo một loại nhóm (GET /api/me/quota, 17/11/2026).
 *
 * context FAMILY = ví phụ huynh (gói PARENT), CLASS = ví giáo viên (gói TEACHER). termStart/termEnd là
 * kỳ gói đang chạy — hết kỳ thì used đếm lại từ 0.
 */
public record QuotaResponse(String context, String plan, int base, int extra, int limit, int used,
                            int remaining, java.time.LocalDate termStart, java.time.LocalDate termEnd) {
}
