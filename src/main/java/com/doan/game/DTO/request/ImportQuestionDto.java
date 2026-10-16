package com.doan.game.DTO.request;

/** Một câu nhập kho (POST /api/admin/questions/import, upsert theo externalId). chapter = N → câu hậu trắc của chương N; null → kho chung cho giáo viên (16/10). */
public record ImportQuestionDto(String externalId, String pool, String stem, java.util.List<String> options, int correctIndex, String standard, String concept, String source, String license, Integer chapter) {
}
