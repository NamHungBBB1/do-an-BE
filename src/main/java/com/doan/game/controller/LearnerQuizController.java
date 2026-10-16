package com.doan.game.controller;

import com.doan.game.DTO.request.SubmitQuizRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.LearnerQuizListResponse;
import com.doan.game.DTO.response.LearnerQuizResponse;
import com.doan.game.DTO.response.SubmitQuizResponse;
import com.doan.game.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * Cổng /api/learner/** đứng trước anyRequest, yêu cầu SCOPE_CHILD — token người lớn 403. Slot lấy
 * từ sub của token SLOT, trẻ chỉ thấy dữ liệu của chính mình (cùng một cửa với dashboard).
 */
@RestController
@RequestMapping("/api/learner/quizzes")
@RequiredArgsConstructor
public class LearnerQuizController {

    private final QuizService quizService;

    /**
     * Danh sách của em — CHỖ HẬU TRẮC "TỰ MỞ": với mỗi chương đã xong mà lớp chưa có đề hậu trắc
     * thì đọc danh sách này chính là lúc BE tạo (một điểm tạo duy nhất, không chạm PlayService).
     */
    @GetMapping
    public ApiResponse<LearnerQuizListResponse> listForLearner(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(quizService.listForLearner(currentSlotId(jwt)));
    }

    /** Đề để làm: KHÔNG có correctIndex; đã nộp thì kèm submission. */
    @GetMapping("/{id}")
    public ApiResponse<LearnerQuizResponse> getForLearner(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable UUID id) {
        return ApiResponse.ok(quizService.getForLearner(currentSlotId(jwt), id));
    }

    /** Nộp một lần — máy chủ chấm, khoá dòng slot, nộp lần hai → 8005 chứ không 500. */
    @PostMapping("/{id}/submit")
    public ApiResponse<SubmitQuizResponse> submitQuiz(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable UUID id,
                                                      @RequestBody SubmitQuizRequest req) {
        return ApiResponse.ok(quizService.submitQuiz(currentSlotId(jwt), id, req));
    }

    /** Token SLOT: subject là id slot, KHÔNG phải id tài khoản (trẻ không có tài khoản). */
    private static UUID currentSlotId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

}
