package com.doan.game.controller;

import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.ChapterBatchResponse;
import com.doan.game.DTO.response.RunStateResponse;
import com.doan.game.DTO.request.ChapterBatchRequest;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.service.PlayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * Cổng /api/play/** yêu cầu SCOPE_CHILD (token SLOT) — token người lớn 403/3004. Slot lấy từ
 * sub của token, FE không gửi slotId / learnerId. Gói hết hạn vẫn nhận lô (Hưng 03/10).
 */
@RestController
@RequestMapping("/api/play")
@RequiredArgsConstructor
public class PlayController {

    /** Trần body lô (Hưng chốt 12/10): 64 KB — quá thì 413/2003 như quá 100 dòng một mảng. */
    private static final long MAX_BATCH_BYTES = 64 * 1024;

    private final PlayService playService;

    /** Cuối chương gửi đúng một lô; giữa chương không gửi gì. Gửi lại vô hại. */
    @PostMapping("/chapter")
    public ApiResponse<ChapterBatchResponse> submitChapterBatch(@AuthenticationPrincipal Jwt jwt,
                                                                @RequestBody ChapterBatchRequest req,
                                                                HttpServletRequest httpReq) {
        // Content-Length: MockMvc luôn set, client thật có thì mới kiểm được — thiếu thì bỏ qua.
        if (httpReq.getContentLengthLong() > MAX_BATCH_BYTES) {
            throw new AppException(ErrorCode.BATCH_TOO_LARGE);
        }
        return ApiResponse.ok(playService.submitChapterBatch(currentSlotId(jwt), req));
    }

    /** Bản đồ chương đọc tiến độ; màn dashboard vẫn gọi GET /api/learner/dashboard. */
    @GetMapping("/state")
    public ApiResponse<RunStateResponse> getRunState(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(playService.getRunState(currentSlotId(jwt)));
    }

    /** Token SLOT: subject là id slot, KHÔNG phải id tài khoản (trẻ không có tài khoản). */
    private static UUID currentSlotId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

}
