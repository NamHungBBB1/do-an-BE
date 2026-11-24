package com.doan.game.controller;

import com.doan.game.DTO.response.AiBatchResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.PageResponse;
import com.doan.game.service.impl.AiJobQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Admin theo dõi lô AI (25/11, gom lô) — chỉ đọc. Cổng /api/admin/** chỉ SCOPE_ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/admin/ai-batches")
@RequiredArgsConstructor
public class AiBatchAdminController {

    private final AiJobQueue aiJobQueue;

    /** Mỗi lần gọi có mã lô một dòng, mới nhất trước; lọc tuỳ chọn khoá, kết quả, khoảng thời gian (from ≤ t < to). */
    @GetMapping
    public ApiResponse<PageResponse<AiBatchResponse>> list(@RequestParam(required = false) String keyAlias,
                                                           @RequestParam(required = false) String outcome,
                                                           @RequestParam(required = false) Instant from,
                                                           @RequestParam(required = false) Instant to,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(aiJobQueue.batches(keyAlias, outcome, from, to, page, size));
    }

    /** Một lô kèm từng việc; không có → 1003. */
    @GetMapping("/{batchId}")
    public ApiResponse<AiBatchResponse> get(@PathVariable UUID batchId) {
        return ApiResponse.ok(aiJobQueue.batch(batchId));
    }
}
