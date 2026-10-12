package com.doan.game.DTO.request;

/** Lô cuối chương (play.md 12/10): chỉ ghi đã chọn gì; điểm, chỉ số, correct, huy hiệu do BE tính từ bản phát hành đã đóng băng. releaseVersion = SemVer của bản đã chơi. */
public record ChapterBatchRequest(int chapter, int attemptIndex, String releaseVersion, java.util.List<ChoiceDto> choices, java.util.List<MiniGameDto> miniGames, java.time.Instant finishedAt) {
}
