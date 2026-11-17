package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.QuotaResponse;
import com.doan.game.enums.LearningContext;
import com.doan.game.service.SlotQuota;
import com.doan.game.service.SlotQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ví slot của chính người gọi (17/11/2026). Tầng này MỎNG: nhận, gọi service, đổi sang DTO.
 *
 * Trả một dòng cho mỗi gói đang chạy: PARENT → context FAMILY, TEACHER → context CLASS. Không có gói
 * nào đang chạy thì mảng rỗng (mua gói là việc của /api/payments, không phải ở đây).
 */
@RestController
@RequestMapping("/api/me/quota")
@RequiredArgsConstructor
public class QuotaController {

    private final SlotQuotaService quotaService;

    @GetMapping
    public ApiResponse<List<QuotaResponse>> myQuotas(@AuthenticationPrincipal Jwt jwt) {
        UUID accountId = UUID.fromString(jwt.getSubject());
        List<QuotaResponse> result = new ArrayList<>();
        for (SlotQuota q : quotaService.quotas(accountId)) {
            result.add(new QuotaResponse(
                    contextOf(q).name(),
                    q.kind().name(),
                    q.base(),
                    q.extra(),
                    q.limit(),
                    q.used(),
                    q.remaining(),
                    q.termStart(),
                    q.termEnd()));
        }
        return ApiResponse.ok(result);
    }

    /** Ngược của SlotQuotaServiceImpl.kindOf — giữ ở controller để DTO không phải biết enum gói. */
    private static LearningContext contextOf(SlotQuota quota) {
        return quota.kind() == com.doan.game.enums.PlanKind.PARENT
                ? LearningContext.FAMILY : LearningContext.CLASS;
    }
}
