package com.doan.game.DTO.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/slots/{id}/report (Hưng duyệt 12/10, build/api-docs/report.md): mọi thứ người lớn
 * được xem về MỘT học sinh. Người lớn thấy 5 chỉ số và mức nắm khái niệm — trẻ không bao giờ thấy.
 * Hình dạng giữ nguyên qua các đợt: khối chưa có dữ liệu trả rỗng / null, FE dựng giao diện một lần.
 * Không pinHash, không email.
 */
public record SlotReportResponse(Learner learner, Progress progress, Indicators indicators,
                                 List<AchievementRow> achievements, List<ConceptRow> concepts,
                                 List<ChapterRow> chapters, Quizzes quizzes, DailySummaryRow dailySummary) {

    /** WIPED: code, displayName = null (tên đọc sống, số đóng băng — ERD); FE hiện "Đã xoá". */
    public record Learner(UUID id, String code, String displayName, String badge, String status,
                          GroupRef group, Instant createdAt, Instant archivedAt, Instant wipedAt,
                          Instant lastActiveAt) {}

    public record GroupRef(UUID id, String name, String context, Instant closedAt) {}

    /** Cùng nguồn và cùng nghĩa với progress của dashboard trẻ + attemptIndex (lần chơi hiện tại). */
    public record Progress(Integer totalChapters, int completedChapters, List<Integer> chaptersDone,
                           Integer continueChapter, int attemptIndex, Instant lastPlayedAt) {}

    /** 5 chỉ số ẩn 0–100 từ RunState — chỉ người lớn thấy; chưa chơi → null, không bịa số 50. */
    public record Indicators(Integer wealth, Integer saving, Integer happiness, Integer risk, Integer goal) {}

    public record AchievementRow(String code, Instant earnedAt) {}

    /** Mức nắm khái niệm (BKT, đợt 2 — nay luôn rỗng nhưng giữ hình). pMastery 0–1, ngưỡng nắm 0.95. */
    public record ConceptRow(String concept, Double pMastery, Integer answers) {}

    /** Từng chương đã xong trong lần chơi hiện tại (đợt 2). Lựa chọn là thiên hướng, không đúng sai. */
    public record ChapterRow(int chapter, int attemptIndex, Instant finishedAt,
                             List<ChoiceRow> choices, List<MiniGameRow> miniGames) {}

    public record ChoiceRow(String sceneId, String optionId, String label) {}

    public record MiniGameRow(String gameId, String itemId, String concept, String chosen, Boolean correct) {}

    /** null với FAMILY (quiz chỉ cho lớp); CLASS: object với ba trường, results chờ API quiz. */
    public record Quizzes(int assigned, int submitted, List<QuizResultRow> results) {}

    public record QuizResultRow(UUID quizId, String title, Integer score, Integer totalQuestions,
                                Instant submittedAt) {}

    /** Bản tổng kết AI — làm sau (Hưng 12/10); đợt 1 luôn null. */
    public record DailySummaryRow(LocalDate date, String text, String source) {}
}
