package com.doan.game.controller;

import com.doan.game.DTO.request.NewQuestionDto;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.DTO.response.SavedQuestionResponse;
import com.doan.game.service.TeacherQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 *
 * "Câu của tôi" của giáo viên (quiz v3, 16/11). Cổng rơi vào anyRequest → chỉ token ACCOUNT; gói TEACHER, chủ câu
 * service tự kiểm.
 */
@RestController
@RequiredArgsConstructor
public class TeacherQuestionController {

    private final TeacherQuestionService teacherQuestionService;

    /** Câu chưa rút của tôi, mới nhất trước, kèm usedInPublished. */
    @GetMapping("/api/questions/mine")
    public ApiResponse<List<QuestionResponse>> listMine(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(teacherQuestionService.listMine(currentAccountId(jwt)));
    }

    /** Lưu lô 1–100 câu → {index, id, reused}; câu trùng câu chưa rút của tôi dùng lại id cũ. Lỗi theo index (1001). */
    @PostMapping("/api/questions/mine")
    public ApiResponse<List<SavedQuestionResponse>> saveMine(@AuthenticationPrincipal Jwt jwt,
                                                             @RequestBody List<NewQuestionDto> questions) {
        return ApiResponse.ok(teacherQuestionService.saveMine(currentAccountId(jwt), questions));
    }

    /** Sửa câu của tôi; câu đang nằm trong đề đã phát → 8009. */
    @PutMapping("/api/questions/mine/{id}")
    public ApiResponse<QuestionResponse> updateMine(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                    @RequestBody NewQuestionDto req) {
        return ApiResponse.ok(teacherQuestionService.updateMine(currentAccountId(jwt), id, req));
    }

    /** Chưa từng vào đề → xoá cứng; đã vào đề → ẩn. */
    @DeleteMapping("/api/questions/mine/{id}")
    public ApiResponse<Void> deleteMine(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        teacherQuestionService.deleteMine(currentAccountId(jwt), id);
        return ApiResponse.ok();
    }

    /** Sao câu kho chung thành câu của tôi (copiedFromId); câu hậu trắc / của người khác → 8007. */
    @PostMapping("/api/questions/{id}/copy")
    public ApiResponse<QuestionResponse> copy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(teacherQuestionService.copy(currentAccountId(jwt), id));
    }

    private static UUID currentAccountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
