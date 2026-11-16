package com.doan.game.DTO.response;

/** Câu hỏi cho NGƯỜI LỚN (giáo viên chọn câu, Studio xem kho, xem kết quả): có correctIndex. Không bao giờ trả cho token SLOT. retiredAt / replacesId (17/10): đã rút lúc nào, thay cho câu nào — kho giáo viên luôn null. 16/11 (v3): reference, keepOptionOrder, copiedFromId; usedInPublished chỉ có ở GET /api/questions/mine (số đề đã phát đang dùng câu). */
public record QuestionResponse(java.util.UUID id, String externalId, String pool, String stem, java.util.List<String> options, int correctIndex, String standard, String concept, String source, Integer chapter, java.time.Instant retiredAt, java.util.UUID replacesId, com.doan.game.DTO.request.QuestionReferenceDto reference, boolean keepOptionOrder, java.util.UUID copiedFromId, Integer usedInPublished) {
}
