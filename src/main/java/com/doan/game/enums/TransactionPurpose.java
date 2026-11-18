package com.doan.game.enums;

/**
 * Mục đích một đơn PayOS. `RENEW` không còn sinh ra từ 18/11/2026 (gói vĩnh viễn) — giữ lại chỉ để
 * đọc đúng những dòng cũ trong bảng.
 */
public enum TransactionPurpose {
    NEW,
    RENEW,
    /** Mua gói slot thêm (pack) cho ví đã có gói gốc cùng loại. */
    ADDON
}
