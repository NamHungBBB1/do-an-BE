package com.doan.game.DTO.response;

import com.doan.game.enums.ArchiveAction;
import com.doan.game.enums.ArchiveKind;

import java.time.Instant;

/** Một dòng nhật ký lưu trữ; actorName = null khi do hệ thống. */
public record ArchiveLogResponse(Instant at, ArchiveAction action, ArchiveKind kind, String ref, String actorName,
                                 String note) {
}
