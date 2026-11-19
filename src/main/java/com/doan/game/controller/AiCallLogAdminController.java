package com.doan.game.controller;

import com.doan.game.DTO.response.AiCallLogResponse;
import com.doan.game.DTO.response.AiKeyHealthResponse;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.PageResponse;
import com.doan.game.service.impl.AiCallLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Nhật ký gọi AI + sức khoẻ khoá (đợt D, khung báo cáo AI 13.4) — chỉ đọc. Cổng /api/admin/** chỉ SCOPE_ADMIN. */
@RestController
@RequiredArgsConstructor
public class AiCallLogAdminController {

    private final AiCallLogService service;

    /** Lọc tuỳ chọn keyAlias, outcome, kind, from / to (ISO-8601, from ≤ createdAt < to); mới nhất trước; size ≤ 100. */
    @GetMapping("/api/admin/ai-call-logs")
    public ApiResponse<PageResponse<AiCallLogResponse>> list(@RequestParam(required = false) String keyAlias,
                                                             @RequestParam(required = false) String outcome,
                                                             @RequestParam(required = false) String kind,
                                                             @RequestParam(required = false) Instant from,
                                                             @RequestParam(required = false) Instant to,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.list(keyAlias, outcome, kind, from, to, page, size));
    }

    /** Sức khoẻ từng khoá hôm nay (ngày quota Gemini) + cảnh báo khi còn dưới 2 khoá khoẻ. */
    @GetMapping("/api/admin/ai-keys/health")
    public ApiResponse<AiKeyHealthResponse> health() {
        return ApiResponse.ok(service.health());
    }
}
