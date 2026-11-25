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
                                  Summary summary, List<ReportRow> rows, List<ClassChapter> chapters) {

    /**
     * Báo cáo lớp theo chương (25/11, spec báo cáo lớp): chỉ nhóm CLASS, nhóm FAMILY là mảng rỗng. Số liệu tính sống; nhận xét
     * cả lớp lấy bản mới nhất. Không lộ cơ chế bên trong (mốc, hàng chờ, AI): comment.state chỉ có WAITING | PREPARING |
     * READY | BASIC. learners / wrongLearners / notFinished / notSubmitted có tên em — chỉ chủ nhóm đọc được (requireOwner).
     */
    public record ClassChapter(int chapter, String title, UUID quizId, int active, int finished, int submitted, Comment comment,
                               List<HistoryItem> history, List<ClassConcept> concepts, List<ClassDecision> decisions,
                               List<LearnerRef> notFinished, List<LearnerRef> notSubmitted) {}

    /** basedOn = số em đã nộp lúc viết nhận xét. */
    public record Comment(String state, String text, Instant at, Integer basedOn, SlotReportResponse.Sections sections) {}

    public record HistoryItem(Instant at, Integer basedOn, String text, SlotReportResponse.Sections sections) {}

    /** band của LỚP (cộng dồn câu cả lớp; dưới 10 câu → NO_DATA). Mảng xếp nội dung yếu nhất trước. */
    public record ClassConcept(String name, String objective, int correct, int total, String band,
                               List<LearnerRef> wrongLearners) {}

    public record ClassDecision(String question, List<ClassOption> options) {}

    public record ClassOption(String label, int count, List<LearnerRef> learners) {}

    public record LearnerRef(UUID slotId, String displayName) {}

    public record GroupInfo(UUID id, String name, String context, Integer slotLimit,
                            Instant openedAt, Instant closedAt) {}

    /** averageScore = trung bình score trên mọi lượt nộp (số lẻ → Double), null khi chưa có. */
    public record Summary(int learners, int active, int activeLast7Days, int chaptersCompleted,
                          Integer totalChapters, int quizAssigned, int quizSubmitted,
                          Double averageScore) {}

    public record ReportRow(UUID slotId, String code, String displayName, String status,
                            List<Integer> chaptersDone, int completedChapters, int attemptIndex,
                            Instant lastActiveAt, SlotReportResponse.Indicators indicators,
                            int quizAssigned, int quizSubmitted, Double averageScore) {}
}
