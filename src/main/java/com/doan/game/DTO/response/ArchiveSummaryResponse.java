package com.doan.game.DTO.response;

import java.time.Instant;

/**
 * Con số cho huy hiệu "sắp lưu trữ" (27/11). enabled = false khi chức năng tắt (mọi con số 0). lastSync = lần đồng bộ kho lưu trữ
 * gần nhất, null khi chưa có / không đọc được.
 */
public record ArchiveSummaryResponse(boolean enabled, long dueSoonCount, long countingCount, long archivedCount,
                                     long restoringCount, long archivedBytes, LastSync lastSync) {

    public record LastSync(Instant finishedAt, boolean ok) {
    }
}
