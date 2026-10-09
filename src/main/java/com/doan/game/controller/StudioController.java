package com.doan.game.controller;

import com.doan.game.DTO.request.CreateReleaseRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.ChapterSummaryResponse;
import com.doan.game.DTO.response.ReleaseSummaryResponse;
import com.doan.game.service.ContentService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Studio, giai đoạn 1 (09/10): ADMIN lưu nháp và phát hành (SecurityConfig: /api/studio/** → SCOPE_ADMIN).
 * Vai Editor / Reviewer / Manager và bước duyệt thêm ở giai đoạn sau, trên cùng bảng ChapterDraft.
 * Tầng này MỎNG: nhận, gọi service, trả về.
 */
@RestController
@RequestMapping("/api/studio")
@RequiredArgsConstructor
public class StudioController {

    private final ContentService contentService;

    /** Body = nguyên object chương theo hình dạng FE (chapterNV2GameData). Lưu đè bản nháp của chương. */
    @PutMapping("/chapters/{code}/draft")
    public ApiResponse<ChapterSummaryResponse> saveDraft(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable String code,
                                                         @RequestBody JsonNode content) {
        return ApiResponse.ok(contentService.saveDraft(currentAccountId(jwt), code.trim().toUpperCase(), content));
    }

    @GetMapping("/releases")
    public ApiResponse<List<ReleaseSummaryResponse>> listReleases() {
        return ApiResponse.ok(contentService.listReleases());
    }

    @PostMapping("/releases")
    public ApiResponse<ReleaseSummaryResponse> publish(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestBody CreateReleaseRequest req) {
        return ApiResponse.ok(contentService.publish(currentAccountId(jwt), req));
    }

    /** Lùi / tiến bản: đổi con trỏ bản hiện tại, không xoá gì. */
    @PostMapping("/releases/{version}/current")
    public ApiResponse<ReleaseSummaryResponse> setCurrent(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable String version) {
        return ApiResponse.ok(contentService.setCurrent(currentAccountId(jwt), version));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
