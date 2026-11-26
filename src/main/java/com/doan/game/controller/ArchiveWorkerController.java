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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Cửa cho máy lưu trữ (27/11) — cùng cách VoiceWorkerController: không JWT, xác thực bằng header {@code X-Archive-Worker-Key}
 * (ARCHIVE_WORKER_KEY; trống = mọi đường 403). SecurityConfig permitAll đường này; mỗi hàm kiểm khoá trước khi gọi việc.
 */
@RestController
@RequestMapping("/api/archive-worker")
@RequiredArgsConstructor
public class ArchiveWorkerController {

    static final String HEADER = "X-Archive-Worker-Key";
    private final ArchiveService archiveService;

    @PostMapping("/claim")
    public ApiResponse<List<ArchiveTaskResponse>> claim(@RequestHeader(value = HEADER, required = false) String k,
                                                        @RequestBody(required = false) ArchiveClaimRequest body) {
        archiveService.requireWorker(k);
        return ApiResponse.ok(archiveService.claim(body == null ? null : body.limit()));
    }

    @PostMapping("/items/{id}/archived")
    public ApiResponse<Void> archived(@RequestHeader(value = HEADER, required = false) String k, @PathVariable UUID id) {
        archiveService.requireWorker(k);
        archiveService.reportArchived(id);
        return ApiResponse.ok();
    }

    @PostMapping("/items/{id}/failed")
    public ApiResponse<Void> failed(@RequestHeader(value = HEADER, required = false) String k, @PathVariable UUID id,
                                    @RequestBody(required = false) ArchiveErrorRequest body) {
        archiveService.requireWorker(k);
        archiveService.reportFailed(id, body == null ? null : body.error());
        return ApiResponse.ok();
    }

    @GetMapping("/restores")
    public ApiResponse<List<ArchiveTaskResponse>> restores(@RequestHeader(value = HEADER, required = false) String k) {
        archiveService.requireWorker(k);
        return ApiResponse.ok(archiveService.restores());
    }

    @PostMapping("/items/{id}/restored")
    public ApiResponse<Void> restored(@RequestHeader(value = HEADER, required = false) String k, @PathVariable UUID id) {
        archiveService.requireWorker(k);
        archiveService.reportRestored(id);
        return ApiResponse.ok();
    }

    @PostMapping("/items/{id}/restore-failed")
    public ApiResponse<Void> restoreFailed(@RequestHeader(value = HEADER, required = false) String k, @PathVariable UUID id,
                                           @RequestBody(required = false) ArchiveErrorRequest body) {
        archiveService.requireWorker(k);
        archiveService.reportRestoreFailed(id, body == null ? null : body.error());
        return ApiResponse.ok();
    }
}
