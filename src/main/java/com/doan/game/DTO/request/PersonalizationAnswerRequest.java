package com.doan.game.DTO.request;

import java.util.Map;

/** Trẻ gửi câu trả lời: source = CHILD (màn hỏi nhanh) hoặc GAME (game chọn phụ). answers: chiều → giá trị, null = bỏ qua. */
public record PersonalizationAnswerRequest(String source, Map<String, String> answers) {
}
