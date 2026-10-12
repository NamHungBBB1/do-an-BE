package com.doan.game.DTO.response;

/** Trả sau khi nhận lô cuối chương; gửi lại cùng lô trả cùng giá trị. Không có chỉ số. */
public record ChapterBatchResponse(int attemptIndex, Integer totalChapters, java.util.List<Integer> chaptersDone, Integer continueChapter, java.util.List<AchievementResponse> newAchievements) {
}
