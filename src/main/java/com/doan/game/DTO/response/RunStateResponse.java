package com.doan.game.DTO.response;

/** GET /api/play/state cho trẻ: đúng khối progress của dashboard + lần chơi + bản hiện tại. KHÔNG có 5 chỉ số (ẩn với trẻ, 10/10). postTestRequired (BR-53, 19/11): chương kế tiếp khoá tới khi nộp hậu trắc — null = không khoá. */
public record RunStateResponse(String releaseVersion, int attemptIndex, Integer totalChapters, int completedChapters, java.util.List<Integer> chaptersDone, Integer continueChapter, java.time.Instant lastPlayedAt, PostTestRequiredResponse postTestRequired) {
}
