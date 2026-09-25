package com.doan.game.DTO.response;

/** Trạng thái bền của một em. */
public record RunStateResponse(java.util.List<Integer> chaptersDone, int wealth, int saving, int happiness, int risk, int goal) {
}
