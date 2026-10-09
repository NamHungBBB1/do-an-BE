package com.doan.game.DTO.response;

import java.time.Instant;
import java.util.List;

/**
 * Bản phát hành đang dùng (GET /api/content/current): FE lấy version rồi tải từng chương theo
 * GET /api/content/releases/{version}/chapters/{code}. version nằm trong URL nên JSON chương cache được vĩnh viễn.
 */
public record ReleaseSummaryResponse(String version, Instant releasedAt, String note, boolean current,
                                     List<ChapterSummaryResponse> chapters) {
}
