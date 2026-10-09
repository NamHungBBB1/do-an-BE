package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.ReleaseSummaryResponse;
import com.doan.game.service.ContentService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Nội dung game cho FE — CÔNG KHAI (Hưng chốt 08/10: nội dung không bí mật, thứ thu tiền là báo cáo và
 * chỉ số). Tầng này MỎNG: nhận, gọi service, trả về.
 *
 * FE: GET /current → version → GET /releases/{version}/chapters/{code} → nhận đúng object
 * chapterNV2GameData như file data cũ, thêm releaseVersion. Có version trong URL nên trả
 * Cache-Control immutable: trình duyệt / CDN giữ mãi, bản mới là URL mới.
 */
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<ReleaseSummaryResponse>> current() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(ApiResponse.ok(contentService.currentRelease()));
    }

    /** Lối tắt: chương theo bản hiện tại (không cache lâu — bản hiện tại đổi được). */
    @GetMapping("/chapters/{code}")
    public ResponseEntity<ApiResponse<JsonNode>> currentChapter(@PathVariable String code) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(ApiResponse.ok(contentService.currentChapter(code.trim().toUpperCase())));
    }

    @GetMapping("/releases/{version}/chapters/{code}")
    public ResponseEntity<ApiResponse<JsonNode>> releaseChapter(@PathVariable String version,
                                                                @PathVariable String code) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .body(ApiResponse.ok(contentService.releaseChapter(version, code.trim().toUpperCase())));
    }
}
