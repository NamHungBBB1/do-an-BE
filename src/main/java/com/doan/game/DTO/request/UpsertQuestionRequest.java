package com.doan.game.DTO.request;

/** Thêm / sửa một câu trong Studio (17/10, cách 1). Thêm: externalId trống → BE tự sinh; replacesId → câu mới thay câu cũ, câu cũ bị rút cùng giao dịch. Sửa: externalId và replacesId bị bỏ qua; câu đã nằm trong đề đã phát chỉ đổi được nhãn. */
public record UpsertQuestionRequest(String externalId, String pool, String stem, java.util.List<String> options, Integer correctIndex, String standard, String concept, String source, String license, Integer chapter, java.util.UUID replacesId) {
}
