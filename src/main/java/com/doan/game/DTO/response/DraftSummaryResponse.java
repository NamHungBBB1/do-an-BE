package com.doan.game.DTO.response;

import com.doan.game.enums.ChangeClass;
import com.doan.game.enums.DraftStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Trạng thái bản nháp của một chương trong Studio (không kèm nội dung JSON). reviewedRevision khác revision nghĩa là
 * nháp đã sửa sau lần duyệt cuối.
 */
public record DraftSummaryResponse(String code, int chapterNumber, String title, DraftStatus status,
                                   ChangeClass changeClass, int revision, String changeNote,
                                   UUID editorId, String editorName, Instant submittedAt,
                                   UUID reviewerId, String reviewerName, String reviewNote, Instant reviewedAt,
                                   Integer reviewedRevision, Instant updatedAt) {
}
