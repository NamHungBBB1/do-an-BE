package com.doan.game.controller;

import com.doan.game.DTO.request.PersonalizationAnswerRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.PersonalizationResponse;
import com.doan.game.service.impl.PersonalizationService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Cá nhân hoá (spec build/spec-ca-nhan-hoa-2026-11-11.md). Mỏng: nhận, gọi service, trả về.
 * /api/slots/** = phụ huynh (token ACCOUNT, service kiểm chủ nhóm). /api/play/** = trẻ (token SLOT, SecurityConfig chặn theo typ;
 * slot lấy từ sub của token, FE không gửi slotId).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PersonalizationController {

    private final PersonalizationService service;

    @GetMapping("/slots/{id}/personalization")
    public ApiResponse<PersonalizationResponse> parentView(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(service.forParent(UUID.fromString(jwt.getSubject()), id));
    }

    /** Body: {"gender":"FEMALE","age":null,"interest":"SPORT"} — null / bỏ khoá = "không biết, để bé chọn". Ghi đè cả bộ. */
    @PutMapping("/slots/{id}/personalization")
    public ApiResponse<PersonalizationResponse> parentSave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                           @RequestBody Map<String, String> answers) {
        return ApiResponse.ok(service.saveParent(UUID.fromString(jwt.getSubject()), id, answers));
    }

    @GetMapping("/play/personalization")
    public ApiResponse<PersonalizationResponse> childView(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(service.forChild(UUID.fromString(jwt.getSubject())));
    }

    @PutMapping("/play/personalization")
    public ApiResponse<PersonalizationResponse> childSave(@AuthenticationPrincipal Jwt jwt,
                                                          @RequestBody PersonalizationAnswerRequest req) {
        return ApiResponse.ok(service.saveChild(UUID.fromString(jwt.getSubject()),
                req == null ? null : req.source(), req == null ? null : req.answers()));
    }

    /**
     * Chương đã ghép theo nhãn của trẻ — thay cho /api/content/releases/{v}/chapters/{code} khi trẻ chơi. Kèm variantKey.
     * private, no-store: chương đã ghép lộ giới tính / sở thích của trẻ, không cho CDN / proxy giữ.
     */
    @GetMapping("/play/releases/{version}/chapters/{code}")
    public ResponseEntity<ApiResponse<JsonNode>> chapter(@AuthenticationPrincipal Jwt jwt, @PathVariable String version,
                                                         @PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(ApiResponse.ok(service.chapterForChild(UUID.fromString(jwt.getSubject()), version.trim(),
                        code.trim().toUpperCase(java.util.Locale.ROOT))));
    }
}
