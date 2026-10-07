package com.doan.game.exception;

import java.sql.SQLException;

/**
 * Nơi dùng chung các cách đọc lỗi thô từ CSDL.
 *
 * isUniqueViolation trước đây là static package-private của AuthServiceImpl — GroupServiceImpl
 * (khác package) cũng cần nên chuyển ra đây (07/10, feat/group-slot). Chỉ coi là "đã tồn tại"
 * khi SQLState 23505 (unique_violation); lỗi ràng buộc khác (NOT NULL sót ở production) là lỗi
 * hệ thống, phải giữ nguyên 500 chứ không được dịch thành lỗi nghiệp vụ.
 */
public final class DbErrors {

    private DbErrors() {
    }

    /** SQLState 23505 = unique_violation (H2 và PostgreSQL). Lỗi ràng buộc khác không phải "đã tồn tại". */
    public static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
