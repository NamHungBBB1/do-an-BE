package com.doan.game.controller;

import com.doan.game.DTO.request.ArchiveClaimRequest;
import com.doan.game.DTO.request.ArchiveErrorRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.ArchiveTaskResponse;
import com.doan.game.service.ArchiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Cửa cho máy lưu trữ (27/11): không JWT, xác thực bằng header {@code X-Archive-Worker-Key} (ARCHIVE_WORKER_KEY; trống = mọi
 * đường 403). SecurityConfig permitAll đường này; khoá kiểm ở ArchiveWorkerConfig cho mọi đường dưới /api/archive-worker.
 */
@RestController
@RequestMapping("/api/archive-worker")
@RequiredArgsConstructor
public class ArchiveWorkerController {

    private final ArchiveService archiveService;

    @PostMapping("/claim")
    public ApiResponse<List<ArchiveTaskResponse>> claim(@RequestBody(required = false) ArchiveClaimRequest body) {
        return ApiResponse.ok(archiveService.claim(body == null ? null : body.limit()));
    }

    @PostMapping("/items/{id}/archived")
    public ApiResponse<Void> archived(@PathVariable UUID id) {
        archiveService.reportArchived(id);
        return ApiResponse.ok();
    }

    @PostMapping("/items/{id}/failed")
    public ApiResponse<Void> failed(@PathVariable UUID id,
                                    @RequestBody(required = false) ArchiveErrorRequest body) {
        archiveService.reportFailed(id, body == null ? null : body.error());
        return ApiResponse.ok();
    }

    @GetMapping("/restores")
    public ApiResponse<List<ArchiveTaskResponse>> restores() {
        return ApiResponse.ok(archiveService.restores());
    }

    @PostMapping("/items/{id}/restored")
    public ApiResponse<Void> restored(@PathVariable UUID id) {
        archiveService.reportRestored(id);
        return ApiResponse.ok();
    }

    @PostMapping("/items/{id}/restore-failed")
    public ApiResponse<Void> restoreFailed(@PathVariable UUID id,
                                           @RequestBody(required = false) ArchiveErrorRequest body) {
        archiveService.reportRestoreFailed(id, body == null ? null : body.error());
        return ApiResponse.ok();
    }
}
