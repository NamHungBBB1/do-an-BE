package com.doan.game.service.impl;

import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** RunState.chaptersDone lưu dạng mảng JSON text — một chỗ đọc chung cho Play, dashboard người học và admin. */
final class ChaptersDone {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChaptersDone() {
    }

    /** null / rỗng → danh sách rỗng, sửa được (chưa chơi gì); JSON hỏng → 1000 (dữ liệu trong DB sai, không đoán). */
    static List<Integer> parse(String json) {
        List<Integer> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node.isArray()) {
                node.forEach(n -> out.add(n.asInt()));
            }
            return out;
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.UNCATEGORIZED, "chaptersDone hong trong DB");
        }
    }
}
