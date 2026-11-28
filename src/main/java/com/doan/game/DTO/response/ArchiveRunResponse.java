package com.doan.game.DTO.response;

/**
 * Tổng kết một lượt tính vòng đời. aborted = true: phanh an toàn chặn, không đổi gì (note ghi lý do). scannedFiles = số tệp kho
 * trên đĩa; counting = số dòng đang đếm sau lượt; started / cleared = mới đếm / bỏ đếm; reconciled = số dòng đối soát;
 * releasesArchived = số bản phát hành chuyển sang đã lưu trữ.
 */
public record ArchiveRunResponse(boolean aborted, int scannedFiles, long counting, int started, int cleared, int reconciled,
                                 int releasesArchived, String note) {
}
