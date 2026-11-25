package com.doan.game.DTO.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/groups/{id}/reports/course (26/11): tab "Toàn khoá" của báo cáo lớp. Chỉ tính em đang hoạt động (classSize),
 * hồ sơ đã xoá / thu hồi không vào mẫu số. Chỉ chủ nhóm đọc được.
 */
public record ClassCourseReportResponse(int classSize, Integer totalChapters, List<ChapterProgress> chapters,
                                        PostTest postTest, List<Topic> topics, List<Learner> learners) {

    /** Đủ chương 1..totalChapters; chương chưa ai chơi = 0. */
    public record ChapterProgress(int number, String title, int finished, int submitted) {}

    /**
     * Bài sau chương (hậu trắc) của các em đã nộp ít nhất một bài. basedOn = số em đó; average gộp mọi câu đã nộp
     * (null khi chưa ai nộp). distribution: mỗi em một lần, theo % đúng của chính em.
     */
    public record PostTest(int basedOn, Score average, List<ScoreBand> distribution) {}

    public record Score(int correct, int total, int rate) {}

    /** Khoảng % đúng [from, to], đủ bốn khoảng kể cả khi count = 0. */
    public record ScoreBand(String label, int from, int to, int count, List<GroupReportResponse.LearnerRef> learners) {}

    /**
     * Tám chủ đề D01–D08, cộng dồn mọi chương em đã xong (mini-game + bài sau chương) của cả lớp. chapters = các chương
     * có câu thuộc chủ đề trong dữ liệu lớp. level như nội dung theo chương (dưới 10 câu → NO_DATA).
     */
    public record Topic(String code, String name, int correct, int total, Integer correctRate, String level,
                        boolean hasEnoughData, List<Integer> chapters) {}

    public record Learner(UUID slotId, String displayName, String code, int chaptersDone, int postTestsSubmitted,
                          int postTestCorrect, int postTestTotal, Instant lastActiveAt) {}
}
