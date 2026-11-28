package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.ArchiveItemResponse;
import com.doan.game.DTO.response.ArchiveLogResponse;
import com.doan.game.DTO.response.ArchiveRunResponse;
import com.doan.game.DTO.response.ArchiveSummaryResponse;
import com.doan.game.service.ArchiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Lưu trữ trong Studio (27/11): cổng /api/studio mở cho 4 vai nội bộ; Giữ lại / Khôi phục / chạy tay service tra AccountRole. */
@RestController
@RequestMapping("/api/studio/archive")
@RequiredArgsConstructor
public class ArchiveController {

    private final ArchiveService archiveService;

    @GetMapping("/summary")
    public ApiResponse<ArchiveSummaryResponse> summary() {
        return ApiResponse.ok(archiveService.summary());
    }

    @GetMapping("/due-soon")
    public ApiResponse<List<ArchiveItemResponse>> dueSoon() {
        return ApiResponse.ok(archiveService.dueSoon());
    }

    @GetMapping("/archived")
    public ApiResponse<List<ArchiveItemResponse>> archived() {
        return ApiResponse.ok(archiveService.archived());
    }

    @PostMapping("/{id}/keep")
    public ApiResponse<ArchiveItemResponse> keep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(archiveService.keep(UUID.fromString(jwt.getSubject()), id));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ArchiveItemResponse> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(archiveService.restore(UUID.fromString(jwt.getSubject()), id));
    }

    @GetMapping("/log")
    public ApiResponse<List<ArchiveLogResponse>> log(@RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(archiveService.log(limit));
    }

    @PostMapping("/run")
    public ApiResponse<ArchiveRunResponse> run(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(archiveService.run(UUID.fromString(jwt.getSubject())));
    }
}
