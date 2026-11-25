package com.doan.game.DTO.response;

import java.util.List;
import java.util.UUID;

/**
 * GET /api/groups/{id}/reports/chapters/{chapter} (26/11, Triệu đề xuất, Hưng duyệt). BE tính sẵn mẫu số, tỉ lệ, nhóm em;
 * FE chỉ vẽ. Bất biến (có test): bốn nhóm progress không trùng em, tổng = classSize; tổng count các phương án =
 * reachedLearners; count luôn = learners.size(). Chỉ chủ nhóm đọc được (có tên em).
 */
public record ClassChapterReportResponse(ChapterInfo chapter, int classSize, Progress progress,
                                         GroupReportResponse.Comment comment, List<Concept> concepts,
                                         List<StoryDecision> storyDecisions) {

    public record ChapterInfo(int number, String title, UUID quizId) {}

    /**
     * submitted = đã nộp bài sau chương; finishedNotSubmitted = xong chương, chưa nộp (gồm chương chưa có đề);
     * inProgress = đã tới chương này (xong mọi chương trước) mà chưa xong; notStarted = còn ở chương trước.
     */
    public record Progress(Bucket submitted, Bucket finishedNotSubmitted, Bucket inProgress, Bucket notStarted) {}

    public record Bucket(int count, List<GroupReportResponse.LearnerRef> learners) {}

    /** correctRate = % đúng làm tròn, null khi total = 0; hasEnoughData = level != NO_DATA (lớp dưới 10 câu). */
    public record Concept(String name, String objective, int correct, int total, Integer correctRate, String level,
                          boolean hasEnoughData, List<GroupReportResponse.LearnerRef> learnersNeedingReview) {}

    /** reachedLearners = số em đã đi qua tình huống (chơi đúng bản phổ biến nhất của lớp). */
    public record StoryDecision(String question, int reachedLearners, int classSize, List<Option> options) {}

    /** key A, B, C… theo thứ tự phương án trong chương; rate null khi reachedLearners < 5 (ít em thì không ghi %). */
    public record Option(String key, String label, int count, Integer rate, List<GroupReportResponse.LearnerRef> learners) {}
}
