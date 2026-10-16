package com.doan.game.DTO.response;

/** Câu hỏi cho NGƯỜI LỚN (giáo viên chọn câu, xem kết quả): có correctIndex. Không bao giờ trả cho token SLOT. */
public record QuestionResponse(java.util.UUID id, String externalId, String pool, String stem, java.util.List<String> options, int correctIndex, String standard, String concept, String source, Integer chapter) {
}
