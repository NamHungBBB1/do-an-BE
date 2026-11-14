package com.doan.game.DTO.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Một tài khoản nhìn từ màn quản trị: thông tin đăng nhập, vai nội bộ, lịch sử gói, 20 giao dịch mới nhất
 * và số slot theo trạng thái. Không có tên / mã đăng nhập / huy hiệu của trẻ.
 */
public record AdminAccountDetailResponse(AccountInfo account, List<String> roles,
                                         List<EntitlementRow> entitlements,
                                         List<TransactionAdminResponse> transactions,
                                         Map<String, Long> slots) {

    /** Thông tin tài khoản + đường vào: providers và lần đăng nhập gần nhất lấy từ Credential. */
    public record AccountInfo(UUID id, String email, String displayName, Instant createdAt,
                              Instant emailVerifiedAt, boolean mustChangePassword, Instant lockedUntil,
                              List<String> providers, Instant lastLoginAt) {
    }

    /** Một dòng gói: còn hạn hay không, nguồn cấp, đơn mua / người cấp kèm lý do. */
    public record EntitlementRow(String kind, LocalDate startsOn, LocalDate expiresOn, boolean active,
                                 String source, Long orderCode, String grantedByEmail, String reason,
                                 Instant createdAt) {
    }
}
