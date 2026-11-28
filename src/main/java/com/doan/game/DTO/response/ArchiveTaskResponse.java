package com.doan.game.DTO.response;

import com.doan.game.enums.ArchiveKind;

import java.util.UUID;

/** Một việc cho máy lưu trữ: path = đường dẫn tương đối trong kho (a/….webp, v/….mp3). */
public record ArchiveTaskResponse(UUID id, ArchiveKind kind, String path) {
}
