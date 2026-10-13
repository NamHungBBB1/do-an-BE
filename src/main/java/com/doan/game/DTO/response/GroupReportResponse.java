package com.doan.game.DTO.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/groups/{id}/report (Hưng duyệt 12/10, build/api-docs/report.md): mọi slot của một nhóm
 * (cả ARCHIVED / WIPED), mỗi slot một dòng, cộng tổng kết nhóm. Nhóm ĐÃ ĐÓNG lấy bản đóng băng
 * lúc đóng (GroupReport / GroupReportRow, formulaVersion "report-v1"); nhóm đang mở tính sống.
 * Con số đóng băng, còn code / displayName / status đọc SỐNG từ LearnerSlot (xoá trẻ sau khi
 * đóng vẫn đúng). Không phân trang — nhà ≤ 4 slot, lớp ≤ 40; CSV FE tự xuất từ rows.
 */
public record GroupReportResponse(GroupInfo group, Instant frozenAt, String formulaVersion,
                                  Summary summary, List<ReportRow> rows) {

    public record GroupInfo(UUID id, String name, String context, Integer slotLimit,
                            Instant openedAt, Instant closedAt) {}

    /** averageScore = trung bình score trên mọi lượt nộp, null khi chưa có. */
    public record Summary(int learners, int active, int activeLast7Days, int chaptersCompleted,
                          Integer totalChapters, int quizAssigned, int quizSubmitted,
                          Integer averageScore) {}

    public record ReportRow(UUID slotId, String code, String displayName, String status,
                            List<Integer> chaptersDone, int completedChapters, int attemptIndex,
                            Instant lastActiveAt, SlotReportResponse.Indicators indicators,
                            int quizAssigned, int quizSubmitted, Integer averageScore) {}
}
