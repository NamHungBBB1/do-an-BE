package com.doan.game.DTO.response;

/** Bản đông cứng. Con số đóng băng, còn TÊN đọc sống từ slot lúc hiển thị. */
public record GroupReportResponse(java.util.UUID id, java.time.Instant frozenAt, String formulaVersion, java.util.List<ReportRowResponse> rows) {
}
