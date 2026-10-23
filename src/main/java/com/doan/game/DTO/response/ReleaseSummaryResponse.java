package com.doan.game.DTO.response;

import com.doan.game.enums.ChangeClass;

import java.time.Instant;
import java.util.List;

/**
 * Bản phát hành đang dùng (GET /api/content/current): FE lấy version rồi tải từng chương theo
 * GET /api/content/releases/{version}/chapters/{code}. version nằm trong URL nên JSON chương cache được vĩnh viễn.
 * changeClass: loại cao nhất của bản (null với bản phát hành trước PR C). recalledAt khác null = bản đã thu hồi —
 * FE xám bản đó và chuyển sang bản hiện tại ở ranh giới chương kế tiếp.
 */
public record ReleaseSummaryResponse(String version, Instant releasedAt, String note, boolean current,
                                     ChangeClass changeClass, Instant recalledAt,
                                     List<ChapterSummaryResponse> chapters) {
}
