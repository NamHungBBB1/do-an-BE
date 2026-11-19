package com.doan.game.service;

import com.doan.game.enums.AiProviderKind;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Gọi mô hình AI sinh văn bản (24/10, gợi ý cách đọc cho giọng đọc — spec build/spec-kho-anh-giong-2026-10-24.md mục 4.7).
 * Tách giao diện để test thay bằng giả, không gọi mạng thật. Kết quả là chữ thô (JSON do prompt yêu cầu), nơi gọi tự kiểm.
 */
public interface AiTextClient {

    /**
     * Lỗi gọi AI: {@code quota} = hết lượt (402 / 429) — nơi gọi cho khoá nghỉ. {@code code} = mã lỗi ghi lên việc
     * (null → AI_ERROR); {@code fatal} = không thử lại (đầu ra bị loại 2 lần, bộ lọc an toàn, không còn khoá) → FAILED ngay,
     * báo cáo dùng bản theo luật.
     */
    class AiCallException extends RuntimeException {
        private final boolean quota;
        private final String code;
        private final boolean fatal;

        public AiCallException(String message, boolean quota) {
            this(message, quota, null, false);
        }

        public AiCallException(String message, boolean quota, String code, boolean fatal) {
            super(message);
            this.quota = quota;
            this.code = code;
            this.fatal = fatal;
        }

        public boolean quota() {
            return quota;
        }

        public String code() {
            return code;
        }

        public boolean fatal() {
            return fatal;
        }
    }

    /**
     * Một lần gọi HTTP, chưa diễn giải (đợt D, khung báo cáo AI 13.4): status 0 = không có phản hồi HTTP (timeout / mạng),
     * body khi đó là tên lỗi. Nơi gọi tự phân loại lỗi và ghi AiCallLog.
     */
    record RawReply(int status, String body, long latencyMs) {
    }

    /** Model đang dùng cho nhà cung cấp đó (ghi vào kết quả để UI hiện "ai đã sinh ra"). */
    String model(AiProviderKind provider);

    String generateJson(AiProviderKind provider, String key, String prompt);

    /**
     * Gọi một lần, trả nguyên văn. Gemini: systemInstruction + responseMimeType application/json + responseSchema (Gemini
     * OpenAPI subset, null = không ép). OpenRouter: tin system + user, response_format json_object (bỏ qua schema).
     */
    RawReply send(AiProviderKind provider, String key, String model, String system, String user, JsonNode responseSchema);
}
