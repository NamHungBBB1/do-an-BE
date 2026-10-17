package com.doan.game.controller;

import com.doan.game.DTO.request.UpsertQuestionRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.service.QuestionBankService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * Cổng /api/studio/questions/** (SecurityConfig, đứng trước /api/studio/**) cho bốn vai nội bộ ADMIN /
 * GAME_EDITOR / GAME_REVIEWER / GAME_MANAGER; ghi hay không thì service tra vai trong DB (Reviewer chỉ đọc).
 * Kho câu hỏi Studio, Hưng chốt 17/10 (cách 1: khoá câu đã dùng, sửa = tạo câu mới thay thế).
 */
@RestController
@RequestMapping("/api/studio/questions")
@RequiredArgsConstructor
public class StudioQuestionController {

    private final QuestionBankService questionBankService;

    /** Toàn kho kể cả bộ hậu trắc; câu đã rút chỉ khi includeRetired=true. Không phân trang (kho vài trăm câu). */
    @GetMapping
    public ApiResponse<List<QuestionResponse>> list(@RequestParam(required = false) Integer chapter,
                                                    @RequestParam(required = false) String pool,
                                                    @RequestParam(required = false) String standard,
                                                    @RequestParam(required = false) String concept,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "false") boolean includeRetired) {
        return ApiResponse.ok(questionBankService.list(chapter, pool, standard, concept, q, includeRetired));
    }

    /** Thêm một câu; externalId trống → BE tự sinh; replacesId → câu cũ bị rút cùng giao dịch. */
    @PostMapping
    public ApiResponse<QuestionResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                @RequestBody UpsertQuestionRequest req) {
        return ApiResponse.ok(questionBankService.create(currentAccountId(jwt), req));
    }

    /** Sửa; câu đã nằm trong đề đã phát thì chỉ đổi được nhãn (nội dung khác → 409 / 8009). */
    @PutMapping("/{id}")
    public ApiResponse<QuestionResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                @RequestBody UpsertQuestionRequest req) {
        return ApiResponse.ok(questionBankService.update(currentAccountId(jwt), id, req));
    }

    /** Rút khỏi kho (đề cũ giữ nguyên, đề mới không chụp). Idempotent. */
    @PostMapping("/{id}/retire")
    public ApiResponse<QuestionResponse> retire(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(questionBankService.retire(currentAccountId(jwt), id));
    }

    /** Đưa lại vào kho. Idempotent. */
    @PostMapping("/{id}/restore")
    public ApiResponse<QuestionResponse> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(questionBankService.restore(currentAccountId(jwt), id));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

}
