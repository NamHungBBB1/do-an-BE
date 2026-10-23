package com.doan.game.service;

import com.doan.game.enums.AiProviderKind;

/**
 * Hỏi nhà cung cấp AI xem một khoá còn dùng được không — gọi rẻ nhất, không tốn token (24/10, quản lý khoá AI).
 * Tách thành giao diện để test thay bằng giả, không gọi mạng thật.
 */
public interface AiKeyProbe {

    /** OK: dùng được · INVALID: khoá sai / bị thu (400, 401, 403) · QUOTA: hết lượt (402, 429) · ERROR: mạng / lỗi khác. */
    enum Outcome { OK, INVALID, QUOTA, ERROR }

    record Result(Outcome outcome, String detail) {
    }

    Result probe(AiProviderKind provider, String key);
}
