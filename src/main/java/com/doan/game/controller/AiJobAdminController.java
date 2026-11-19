package com.doan.game.controller;

import com.doan.game.DTO.response.AiJobResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.PageResponse;
import com.doan.game.service.impl.AiJobQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Nhật ký việc AI (Hưng 19/11) — chỉ đọc. Cổng /api/admin/** chỉ SCOPE_ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/admin/ai-jobs")
@RequiredArgsConstructor
public class AiJobAdminController {

    private final AiJobQueue aiJobQueue;

    /** Lọc tuỳ chọn status (QUEUED, RUNNING, WAITING_RETRY, WAITING_AGENT, DONE, FAILED) và kind; mới nhất trước. */
    @GetMapping
    public ApiResponse<PageResponse<AiJobResponse>> list(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String kind,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(aiJobQueue.list(status, kind, page, size));
    }

    /** Một việc; không có → 1003. */
    @GetMapping("/{id}")
    public ApiResponse<AiJobResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(aiJobQueue.get(id));
    }
}
