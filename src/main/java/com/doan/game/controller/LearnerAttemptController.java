package com.doan.game.controller;

import com.doan.game.DTO.request.SaveAnswerRequest;
import com.doan.game.DTO.request.SubmitAttemptRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.AttemptResponse;
import com.doan.game.DTO.response.AttemptReviewResponse;
import com.doan.game.service.QuizAttemptService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * Lượt làm quiz v3 (16/11) cho trẻ. Cổng /api/learner/** chỉ nhận token SLOT; slot lấy từ sub, em chỉ thấy lượt của mình.
 */
@RestController
@RequiredArgsConstructor
public class LearnerAttemptController {

    private final QuizAttemptService quizAttemptService;

    /** Mở đề: còn lượt mở thì trả lại, chưa có thì tạo đề riêng đã xáo. Không có correctIndex, không id đáp án gốc. */
    @PostMapping("/api/learner/quizzes/{id}/attempts")
    public ApiResponse<AttemptResponse> open(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(quizAttemptService.open(currentSlotId(jwt), id));
    }

    /** Tự lưu một vị trí {choice, leaveCount}; lượt đã nộp / hết giờ → 8011. */
    @PutMapping("/api/learner/attempts/{attemptId}/answers/{pos}")
    public ApiResponse<Void> saveAnswer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID attemptId,
                                        @PathVariable int pos, @RequestBody(required = false) SaveAnswerRequest req) {
        quizAttemptService.saveAnswer(currentSlotId(jwt), attemptId, pos, req);
        return ApiResponse.ok();
    }

    /** Nộp; nộp lại trả kết quả cũ. */
    @PostMapping("/api/learner/attempts/{attemptId}/submit")
    public ApiResponse<AttemptReviewResponse> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID attemptId,
                                                     @RequestBody(required = false) SubmitAttemptRequest req) {
        return ApiResponse.ok(quizAttemptService.submit(currentSlotId(jwt), attemptId, req));
    }

    /** Xem lại lượt của mình. */
    @GetMapping("/api/learner/attempts/{attemptId}")
    public ApiResponse<AttemptReviewResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID attemptId) {
        return ApiResponse.ok(quizAttemptService.get(currentSlotId(jwt), attemptId));
    }

    /** Token SLOT: subject là id slot, KHÔNG phải id tài khoản. */
    private static UUID currentSlotId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
