package com.doan.game.DTO.request;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Lô cuối chương bản 2 (build/api-docs/play-v2.md): FE ghi việc trẻ đã làm theo seq, BE diễn giải. Không đọc bằng Jackson
 * mà bằng PlayBatchV2.parse — đường bản 2 phải từ chối trường lạ, đường bản 1 thì bỏ qua (FE đang chạy dựa vào đó).
 */
public record ChapterBatchV2Request(int schema, int chapter, int attemptIndex, String releaseVersion, List<Pick> picks,
                                    List<Inspection> inspections, List<MiniGameRow> miniGames,
                                    List<Reflection> reflections, Instant finishedAt) {

    public record Pick(int seq, String sceneId, String optionId, Instant at) {
    }

    public record Inspection(int seq, String sceneId, String inspectId, Instant at) {
    }

    public record MiniGameRow(int seq, String gameId, String itemId, JsonNode chosen, Instant at) {
    }

    /** answer: mã ô → mã phương án (chuỗi) hoặc danh sách mã (mảng chuỗi). */
    public record Reflection(int seq, String sceneId, JsonNode answer, Instant at) {
    }
}
