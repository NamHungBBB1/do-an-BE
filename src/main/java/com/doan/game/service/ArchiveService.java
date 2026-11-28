package com.doan.game.service;

import com.doan.game.DTO.response.ArchiveItemResponse;
import com.doan.game.DTO.response.ArchiveLogResponse;
import com.doan.game.DTO.response.ArchiveRunResponse;
import com.doan.game.DTO.response.ArchiveSummaryResponse;
import com.doan.game.DTO.response.ArchiveTaskResponse;

import java.util.List;
import java.util.UUID;

/**
 * Vòng đời lưu trữ kho ảnh / giọng / bản phát hành (27/11, spec build/spec-luu-tru-be-2026-11-27.md). BE quyết định và ghi trạng
 * thái; máy lưu trữ bên ngoài là nơi duy nhất chạm vào kho lưu trữ và gỡ tệp. Mặc định tắt (app.archive.mode = OFF).
 *
 * Bảng phụ trách: ArchiveItem, ArchiveLog
 */
public interface ArchiveService {

    // ---- Studio (mọi vai nội bộ; chạy tay chỉ ADMIN)
    ArchiveSummaryResponse summary();

    List<ArchiveItemResponse> dueSoon();

    List<ArchiveItemResponse> archived();

    ArchiveItemResponse keep(UUID callerId, UUID id);

    ArchiveItemResponse restore(UUID callerId, UUID id);

    List<ArchiveLogResponse> log(Integer limit);

    ArchiveRunResponse run(UUID callerId);

    // ---- máy lưu trữ (header X-Archive-Worker-Key)
    void requireWorker(String presentedKey);

    List<ArchiveTaskResponse> claim(Integer limit);

    void reportArchived(UUID id);

    void reportFailed(UUID id, String error);

    List<ArchiveTaskResponse> restores();

    void reportRestored(UUID id);

    void reportRestoreFailed(UUID id, String error);
}
