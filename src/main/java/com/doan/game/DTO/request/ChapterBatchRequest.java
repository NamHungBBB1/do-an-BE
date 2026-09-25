package com.doan.game.DTO.request;

/** Một lô gửi lên khi xong một chương. Mang cả ba dòng dữ liệu cùng lúc; (slot, chương, lần chơi) là khoá chống trùng khi lô bị gửi lại. */
public record ChapterBatchRequest(int chapter, int attemptIndex, String buildVersion, java.util.List<ChoiceDto> choices, java.util.List<MiniGameDto> miniGames, IndicatorsDto indicators) {
}
