package com.doan.game.DTO.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin theo dõi lô (25/11, spec báo cáo lớp mục 7): một dòng = một lần gọi AI có mã lô. keyAlias là tên gợi nhớ của khoá,
 * không bao giờ là khoá. Không có gói số liệu hay bài viết. items chỉ có ở GET /api/admin/ai-batches/{batchId}.
 */
public record AiBatchResponse(UUID batchId, String lane, Instant sentAt, String sendReason, Integer size, String keyAlias,
                              String model, Integer latencyMs, Integer tokensIn, Integer tokensOut, String outcome,
                              int passed, int rejected, List<Item> items) {

    /** next: SINGLE_RETRY (gửi lẻ lần sau) | FALLBACK (dùng bản tóm tắt nhanh) | null. */
    public record Item(UUID aiJobId, String kind, String lane, String status, String rejectedRule, String next) {}
}
