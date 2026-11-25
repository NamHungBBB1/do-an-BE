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
                                 List<ChapterRow> chapters, FinalReport finalReport, Quizzes quizzes,
                                 DailySummaryRow dailySummary) {

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

    /**
     * Kiến thức theo mã (19/11, khung báo cáo AI mục 3.1 + 13.1 — THAY hình cũ {concept, pMastery, answers}, BKT để sau).
     * game = câu mini-game lần nộp đầu, postTest = câu hậu trắc đã nộp; correct / total = tổng hai nguồn; band theo BR-44.
     * Chỉ số nguyên, không lưu tỉ lệ (BR-38). 19/11 (đợt D.1): name = tên tiếng Việt ngắn cho phụ huynh, objective = "Nội
     * dung cần đạt" của khung kiến thức v3 (mã ngoài khung → name = mã, objective null).
     */
    public record ConceptRow(String concept, String name, String objective, Count game, Count postTest, int correct,
                             int total, String band) {}

    public record Count(int correct, int total) {}

    /**
     * Từng chương đã xong trong lần chơi hiện tại (19/11). reportStatus: COMPLETE khi hậu trắc SUBMITTED hoặc NONE,
     * còn lại PARTIAL. summary: bài viết của chương (khung báo cáo AI mục 13.3), luôn có object.
     */
    public record ChapterRow(int chapter, int attemptIndex, Instant finishedAt, List<ChoiceRow> choices,
                             MiniGames miniGames, PostTest postTest, String reportStatus, Summary summary) {}

    /**
     * Bài viết cho người lớn (khung báo cáo AI mục 13.3). status: WAITING_POST_TEST (chưa nộp hậu trắc, text null) |
     * GENERATING (việc AI đang chờ / đang chạy — text là bản theo luật, FE gọi lại sau 30 s) | READY (bài AI đã qua kiểm) |
     * FALLBACK (bản theo luật: AI chưa bật, lỗi hoặc bị loại). source RULE | LLM; tier 0 = theo luật, 1–4 = tầng AI
     * (spec kho ảnh + giọng 4.7b); provider = nhà cung cấp AI (null với RULE). generatedAt = lúc bài hiện tại được ghi.
     */
    public record Summary(String status, String text, String source, Integer tier, String provider,
                          Instant generatedAt, Sections sections) {}

    /**
     * Nhận xét có cấu trúc (25/11) — cùng nội dung với text, để FE vẽ thẻ. overview = đoạn mở đầu (null ở bản theo luật);
     * decisions = quyết định trong truyện (em: phương án bé chọn + deliberate; lớp: phương án nhiều em chọn nhất,
     * picked / of = số em); doingWell / needsPractice = khái niệm theo mức (band, correct / total lấy từ số liệu, không từ
     * lời AI); tips = gợi ý cho gia đình / tiết học; notEnough = khái niệm chưa đủ bài để nhận xét. text từng mục = lời
     * nhận xét (null ở bản theo luật). Mọi mảng luôn có (rỗng khi không có gì).
     */
    public record Sections(String overview, List<DecisionNote> decisions, List<ConceptNote> doingWell,
                           List<ConceptNote> needsPractice, List<TipNote> tips, List<ConceptCount> notEnough) {}

    public record DecisionNote(String option, Boolean deliberate, Integer picked, Integer of, String text) {}

    public record ConceptNote(String concept, String name, String band, int correct, int total, String text) {}

    public record TipNote(String concept, String name, String text) {}

    public record ConceptCount(String concept, String name, int correct, int total) {}

    /**
     * Một lựa chọn trong truyện — KHÔNG có đúng sai (BR-45). label = chữ của phương án trong JSON bản bé đã chơi (bản gốc,
     * không ghép nhãn cá nhân hoá). deliberate = "có bước cân nhắc": evidenceScores có I / C / P ≥ 1 → true, toàn 0 → false,
     * không có điểm → null.
     */
    public record ChoiceRow(String sceneId, String optionId, String label, Boolean deliberate) {}

    /** Mini-game của chương, lần nộp đầu: x/y câu đúng + từng câu sai (bé chọn gì). */
    public record MiniGames(int correct, int total, List<WrongItem> wrong) {}

    /**
     * Một câu sai. 19/11 (đợt D.1, đề xuất FE): label = nhãn câu bé thấy; chosenLabel / expectedLabel = ô bé chọn và đáp án
     * viết thành chữ (mã → nhãn trong game; luật số / tập → mô tả ngắn, ví dụ "≥ 200.000đ"); expected = đáp án thô (giá trị
     * eq, hoặc luật JSON); explanation = lời giải thích của câu. Tra từ JSON bản bé đã chơi; không tra được → null.
     */
    public record WrongItem(String gameId, String itemId, String concept, String chosen, String label,
                            String chosenLabel, String expected, String expectedLabel, String explanation) {}

    /**
     * Hậu trắc của chương cho nhóm của bé. status: NONE (chương không có câu hậu trắc), PENDING (chưa nộp; quizId null khi
     * đề chưa được tạo), SUBMITTED (correct / total của lần nộp đầu).
     */
    public record PostTest(UUID quizId, String status, Integer correct, Integer total) {}

    /**
     * Bản tổng kết 8 chương: NONE (chưa xong CH08), PARTIAL, COMPLETE (xong CH08 và mọi chương COMPLETE). summary: null
     * khi NONE; PARTIAL → status WAITING_POST_TEST; COMPLETE → như bài chương.
     */
    public record FinalReport(String status, Summary summary) {}

    /** null với FAMILY (quiz chỉ cho lớp); CLASS: object với ba trường, results chờ API quiz. */
    public record Quizzes(int assigned, int submitted, List<QuizResultRow> results) {}

    public record QuizResultRow(UUID quizId, String title, Integer score, Integer totalQuestions,
                                Instant submittedAt) {}

    /** Bài viết mới nhất của em (chương hoặc tổng kết, 19/11); chưa có → null. date = ngày VN lúc ghi. */
    public record DailySummaryRow(LocalDate date, String text, String source, Integer tier, String provider) {}
}
