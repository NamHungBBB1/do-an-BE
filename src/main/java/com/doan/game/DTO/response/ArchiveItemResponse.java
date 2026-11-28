package com.doan.game.DTO.response;

import com.doan.game.enums.ArchiveKind;
import com.doan.game.enums.ArchiveState;

import java.time.Instant;
import java.util.UUID;

/**
 * Một mục trong vòng đời lưu trữ cho Studio (27/11). ref = đường dẫn tệp (a/….webp, v/….mp3) hoặc số bản; label = nhãn ảnh / câu
 * đọc / số bản. url = null khi tệp gốc không còn trên máy chủ (đã lưu trữ) và với bản phát hành; thumbUrl còn dùng được với ảnh.
 * daysLeft chỉ có khi đang đếm (làm tròn lên, tối thiểu 0).
 */
public record ArchiveItemResponse(UUID id, ArchiveKind kind, String ref, String label, String url, String thumbUrl,
                                  String uploadedByName, Long sizeBytes, Instant dueAt, Integer daysLeft, Instant keptAt,
                                  String keptByName, ArchiveState state, Instant archivedAt, Instant restoreRequestedAt) {
}
