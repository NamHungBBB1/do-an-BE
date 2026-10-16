package com.doan.game.controller;

import com.doan.game.DTO.request.CreateQuizRequest;
import com.doan.game.DTO.request.ImportQuestionDto;
import com.doan.game.DTO.request.UpdateQuizRequest;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.ImportQuestionsResponse;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.DTO.response.QuizDetailResponse;
import com.doan.game.DTO.response.QuizResponse;
import com.doan.game.DTO.response.QuizResultsResponse;
import com.doan.game.DTO.response.QuizSlotResultResponse;
import com.doan.game.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * Cổng rơi vào anyRequest → chỉ token typ ACCOUNT (token trẻ bị 403). Quyền (chủ nhóm CLASS,
 * gói TEACHER, ADMIN) service tự kiểm — vai trong JWT không đáng tin.
 */
@RestController
@RequestMapping("")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    /** Nhập kho (ops): upsert theo externalId, câu không có lựa chọn bỏ qua; token ADMIN. */
    @PostMapping("/api/admin/questions/import")
    public ApiResponse<ImportQuestionsResponse> importQuestions(
            @RequestBody List<ImportQuestionDto> questions) {
        return ApiResponse.ok(quizService.importQuestions(questions));
    }

    /** Kho chung cho giáo viên chọn câu (chưa rút, chapter null) — có correctIndex; thiếu gói 3005. */
    @GetMapping("/api/questions")
    public ApiResponse<List<QuestionResponse>> listQuestions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String standard,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String pool,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(quizService.listQuestions(currentAccountId(jwt), standard, concept,
                pool, q));
    }

    /** Tạo nháp đề cho một lớp CLASS đang mở của người gọi (cần gói TEACHER). */
    @PostMapping("/api/quizzes")
    public ApiResponse<QuizResponse> createQuiz(@AuthenticationPrincipal Jwt jwt,
                                                @RequestBody CreateQuizRequest req) {
        return ApiResponse.ok(quizService.createQuiz(currentAccountId(jwt), req));
    }

    /** Đề của một lớp, cả hai kiểu (nháp / đã publish / hậu trắc), mới nhất trước — chỉ chủ nhóm. */
    @GetMapping("/api/quizzes")
    public ApiResponse<List<QuizResponse>> listQuizzes(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestParam UUID groupId) {
        return ApiResponse.ok(quizService.listQuizzes(currentAccountId(jwt), groupId));
    }

    /** Một đề kèm câu đầy đủ (giáo viên thấy correctIndex) — chỉ chủ nhóm. */
    @GetMapping("/api/quizzes/{id}")
    public ApiResponse<QuizDetailResponse> getQuiz(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable UUID id) {
        return ApiResponse.ok(quizService.getQuiz(currentAccountId(jwt), id));
    }

    /** Sửa title / questionIds / dueAt — chỉ khi chưa publish (đã publish → 8002). */
    @PutMapping("/api/quizzes/{id}")
    public ApiResponse<QuizResponse> updateQuiz(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID id,
                                                @RequestBody UpdateQuizRequest req) {
        return ApiResponse.ok(quizService.updateQuiz(currentAccountId(jwt), id, req));
    }

    /** Xoá nháp — chỉ khi chưa publish. */
    @DeleteMapping("/api/quizzes/{id}")
    public ApiResponse<Void> deleteQuiz(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        quizService.deleteQuiz(currentAccountId(jwt), id);
        return ApiResponse.ok();
    }

    /** Publish: từ đó học sinh thấy đề, đề bất biến. */
    @PostMapping("/api/quizzes/{id}/publish")
    public ApiResponse<QuizResponse> publish(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable UUID id) {
        return ApiResponse.ok(quizService.publish(currentAccountId(jwt), id));
    }

    /** Kết quả cả lớp + summary — ACTIVE trước, slot đã nộp mà không còn ACTIVE vẫn vào dòng. */
    @GetMapping("/api/quizzes/{id}/results")
    public ApiResponse<QuizResultsResponse> getClassResults(@AuthenticationPrincipal Jwt jwt,
                                                            @PathVariable UUID id) {
        return ApiResponse.ok(quizService.getClassResults(currentAccountId(jwt), id));
    }

    /** Từng câu của một em (giáo viên thấy đáp án); chưa nộp → 8003. */
    @GetMapping("/api/quizzes/{id}/results/{slotId}")
    public ApiResponse<QuizSlotResultResponse> getSlotResult(@AuthenticationPrincipal Jwt jwt,
                                                             @PathVariable UUID id,
                                                             @PathVariable UUID slotId) {
        return ApiResponse.ok(quizService.getSlotResult(currentAccountId(jwt), id, slotId));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

}
