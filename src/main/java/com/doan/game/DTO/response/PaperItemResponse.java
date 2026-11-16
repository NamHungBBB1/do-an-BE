package com.doan.game.DTO.response;

/** Một câu trong đề riêng của một em, cho NGƯỜI LỚN (16/11, v3): options theo thứ tự em thấy, chosen / correctPos là vị trí trong thứ tự đó (chosen null = bỏ trống). */
public record PaperItemResponse(int pos, java.util.UUID questionId, String stem, java.util.List<String> options, Integer chosen, boolean correct, int correctPos) {
}
