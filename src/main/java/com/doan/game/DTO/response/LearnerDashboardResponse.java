package com.doan.game.DTO.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard người học — contract Hưng duyệt 08/10 (đề xuất của Triệu,
 * build/api-docs/learner-dashboard.md).
 *
 * Hình dạng giữ nguyên qua các đợt: khối nào chưa có dữ liệu thì trả rỗng, FE dựng giao diện
 * một lần. Đợt 1 chỉ có learner đầy đủ; progress / quizzes / achievements / recentActivities
 * trả mặc định. Không 5 chỉ số ẩn, không xu, không bao giờ có PIN.
 */
public record LearnerDashboardResponse(
        Learner learner,
        Progress progress,
        /** null nếu FAMILY — quiz chỉ cho lớp (Hưng chốt 08/10); CLASS: object {pending, completed}. */
        Quizzes quizzes,
        List<Achievement> achievements,
        List<RecentActivity> recentActivities) {

    /** CHILD hay STUDENT không có trường riêng — suy từ groupContext (FAMILY = con, CLASS = học sinh). */
    public record Learner(UUID id, String displayName, String badge, String groupContext, String groupName) {}

    /**
     * "Tiếp tục học" ở mức CHƯƠNG: tiến độ chỉ lưu khi xong chương, nên tiếp tục từ đầu chương
     * chưa xong. Chưa chơi gì → 1, xong hết → null. totalChapters null tới khi có API nội dung game.
     */
    public record Progress(Integer totalChapters, int completedChapters, List<Integer> chaptersDone,
                            Integer continueChapter, Instant lastPlayedAt) {}

    public record Quizzes(List<PendingQuiz> pending, List<CompletedQuiz> completed) {}

    public record PendingQuiz(UUID id, String title, Instant dueAt) {}

    public record CompletedQuiz(UUID id, String title, Integer score, Instant submittedAt) {}

    /** code → FE tự map tên / ảnh. */
    public record Achievement(String code, Instant earnedAt) {}

    /** Tối đa 10, mới nhất trước, không phân trang. type: CHAPTER_DONE | QUIZ_SUBMITTED | ACHIEVEMENT. */
    public record RecentActivity(String type, String label, Instant at) {}
}
