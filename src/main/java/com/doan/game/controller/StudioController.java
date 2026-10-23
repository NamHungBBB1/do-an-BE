package com.doan.game.controller;

import com.doan.game.DTO.request.CreateReleaseRequest;
import com.doan.game.DTO.request.RecallReleaseRequest;
import com.doan.game.DTO.request.ReviewDraftRequest;
import com.doan.game.DTO.request.SubmitDraftRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.ChapterSummaryResponse;
import com.doan.game.DTO.response.DraftContentResponse;
import com.doan.game.DTO.response.DraftSavedResponse;
import com.doan.game.DTO.response.DraftSummaryResponse;
import com.doan.game.DTO.response.ReleasePlanResponse;
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
 * Studio (PR B, 21/10): cổng mở cho ADMIN và ba vai Editor / Reviewer / Manager; ai làm được việc gì thì service
 * tra AccountRole trong DB. Tầng này MỎNG: nhận, gọi service, trả về.
 */
@RestController
@RequestMapping("/api/studio")
@RequiredArgsConstructor
public class StudioController {

    private final ContentService contentService;

    /** Body = nguyên object chương theo hình dạng FE (chapterNV2GameData). Lưu đè bản nháp của chương. */
    @PutMapping("/chapters/{code}/draft")
    public ApiResponse<DraftSavedResponse> saveDraft(@AuthenticationPrincipal Jwt jwt,
                                                         @PathVariable String code,
                                                         @RequestBody JsonNode content) {
        return ApiResponse.ok(contentService.saveDraft(currentAccountId(jwt), code.trim().toUpperCase(), content));
    }

    /** Bộ khuôn nội dung (BE quyết khung): Studio dựng form, FE game kiểm schemaVersion. */
    @GetMapping("/scene-schema")
    public ApiResponse<JsonNode> sceneSchema() {
        return ApiResponse.ok(contentService.sceneSchema());
    }

    /** Nháp mới nhất của một chương KÈM nội dung — Editor sửa tiếp, Reviewer chơi thử. */
    @GetMapping("/chapters/{code}/draft")
    public ApiResponse<DraftContentResponse> draft(@PathVariable String code) {
        return ApiResponse.ok(contentService.draftContent(code.trim().toUpperCase()));
    }

    /** Trạng thái nháp mới nhất của mọi chương (không kèm nội dung). */
    @GetMapping("/chapters")
    public ApiResponse<List<DraftSummaryResponse>> listDrafts() {
        return ApiResponse.ok(contentService.listDrafts());
    }

    @PostMapping("/chapters/{code}/submit")
    public ApiResponse<DraftSummaryResponse> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable String code,
                                                    @RequestBody SubmitDraftRequest req) {
        return ApiResponse.ok(contentService.submitDraft(currentAccountId(jwt), code.trim().toUpperCase(), req));
    }

    @PostMapping("/chapters/{code}/withdraw")
    public ApiResponse<DraftSummaryResponse> withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
        return ApiResponse.ok(contentService.withdrawDraft(currentAccountId(jwt), code.trim().toUpperCase()));
    }

    @PostMapping("/chapters/{code}/review")
    public ApiResponse<DraftSummaryResponse> review(@AuthenticationPrincipal Jwt jwt, @PathVariable String code,
                                                    @RequestBody ReviewDraftRequest req) {
        return ApiResponse.ok(contentService.reviewDraft(currentAccountId(jwt), code.trim().toUpperCase(), req));
    }

    @GetMapping("/releases")
    public ApiResponse<List<ReleaseSummaryResponse>> listReleases() {
        return ApiResponse.ok(contentService.listReleases());
    }

    /** Xem trước bản sắp phát hành: số hiệu BE sẽ tính + các nháp đã duyệt sẽ vào bản. Không ghi gì. */
    @GetMapping("/releases/next")
    public ApiResponse<ReleasePlanResponse> previewRelease(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(contentService.previewRelease(currentAccountId(jwt)));
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

    /** Thu hồi bản đang chạy (lý do bắt buộc): lùi về bản cũ hơn chưa thu hồi; bản bị thu hồi không dùng lại được. */
    @PostMapping("/releases/{version}/recall")
    public ApiResponse<ReleaseSummaryResponse> recall(@AuthenticationPrincipal Jwt jwt, @PathVariable String version,
                                                      @RequestBody RecallReleaseRequest req) {
        return ApiResponse.ok(contentService.recall(currentAccountId(jwt), version, req));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
