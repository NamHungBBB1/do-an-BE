package com.doan.game.service;

import com.doan.game.enums.AiProviderKind;

/**
 * Gọi mô hình AI sinh văn bản (24/10, gợi ý cách đọc cho giọng đọc — spec build/spec-kho-anh-giong-2026-10-24.md mục 4.7).
 * Tách giao diện để test thay bằng giả, không gọi mạng thật. Kết quả là chữ thô (JSON do prompt yêu cầu), nơi gọi tự kiểm.
 */
public interface AiTextClient {

    /** Lỗi gọi AI: {@code quota} = hết lượt (402 / 429) — nơi gọi cho khoá nghỉ. */
    class AiCallException extends RuntimeException {
        private final boolean quota;

        public AiCallException(String message, boolean quota) {
            super(message);
            this.quota = quota;
        }

        public boolean quota() {
            return quota;
        }
    }

    /** Model đang dùng cho nhà cung cấp đó (ghi vào kết quả để UI hiện "ai đã sinh ra"). */
    String model(AiProviderKind provider);

    String generateJson(AiProviderKind provider, String key, String prompt);
}
